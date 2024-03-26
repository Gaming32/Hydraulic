package org.geysermc.hydraulic.pack;

import com.google.common.collect.ListMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import org.apache.commons.io.function.IOStream;
import org.geysermc.event.Event;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.hydraulic.HydraulicImpl;
import org.geysermc.hydraulic.pack.context.PackEventContext;
import org.geysermc.hydraulic.pack.context.PackPostProcessContext;
import org.geysermc.hydraulic.pack.context.PackPreProcessContext;
import org.geysermc.hydraulic.pack.converter.CustomModelConverter;
import org.geysermc.hydraulic.platform.mod.ModInfo;
import org.geysermc.pack.converter.PackConverter;
import org.geysermc.pack.converter.converter.ActionListener;
import org.geysermc.pack.converter.converter.Converter;
import org.geysermc.pack.converter.converter.model.ModelConverter;
import org.geysermc.pack.converter.converter.model.ModelStitcher;
import org.geysermc.pack.converter.data.ConversionData;
import org.geysermc.pack.converter.util.LogListener;
import org.geysermc.pack.converter.util.NioDirectoryFileTreeReader;
import org.geysermc.pack.converter.util.VanillaPackProvider;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import team.unnamed.creative.ResourcePack;
import team.unnamed.creative.model.Model;
import team.unnamed.creative.serialize.minecraft.MinecraftResourcePackReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Manages packs within Hydraulic. Most of the pack conversion
 * management is done within this class, and it is also responsible
 * for loading the packs onto the server.
 */
public class PackManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    static final Set<String> IGNORED_MODS = Set.of(
            "minecraft",
            "java",
            "geyser-fabric",
            "geyser-forge",
            "floodgate",
            "vanilla",
            "mixinextras"
    );

    private final HydraulicImpl hydraulic;
    private final List<PackModule<?>> modules = new ArrayList<>();

    private final ListMultimap<String, ModInfo> namespacesToMods = MultimapBuilder.hashKeys().arrayListValues(1).build();
    private final ListMultimap<String, ResourceLocation> modsToBlocks = MultimapBuilder.hashKeys().arrayListValues().build();
    private final ListMultimap<String, ResourceLocation> modsToItems = MultimapBuilder.hashKeys().arrayListValues().build();

    private ModelStitcher.Provider modelProvider;

    public PackManager(HydraulicImpl hydraulic) {
        this.hydraulic = hydraulic;
    }

    /**
     * Initializes the pack manager.
     */
    public void initialize() {
        initializeModLookups();

        final Collection<ModInfo> mods = this.hydraulic.mods();
        final Map<String, List<ResourcePack>> modPacks = Maps.newHashMapWithExpectedSize(mods.size());
        for (final ModInfo mod : mods) {
            modPacks.put(
                mod.id(),
                mod.roots()
                    .stream()
                    .map(path -> MinecraftResourcePackReader.minecraft().read(NioDirectoryFileTreeReader.read(path)))
                    .toList()
            );
        }
        modelProvider = createModelProvider(mods, modPacks, new PackLogListener(LOGGER));

        for (PackModule<?> module : ServiceLoader.load(PackModule.class)) {
            this.modules.add(module);

            GeyserApi.api().eventBus().register(this.hydraulic, module);
            module.eventListeners().forEach((eventClass, listeners) -> {
                GeyserApi.api().eventBus().subscribe(this.hydraulic, eventClass, this::callEvents);
            });

            for (ModInfo mod : mods) {
                if (IGNORED_MODS.contains(mod.id())) {
                    continue;
                }

                if (module.hasPreProcessors()) {
                    try {
                        module.preProcess0(new PackPreProcessContext(
                            this.hydraulic, mod, module, modPacks.get(mod.id()), modelProvider
                        ));
                    } catch (Throwable t) {
                        LOGGER.error("Failed to pre-process mod {} for module {}", mod.id(), module.getClass().getSimpleName(), t);
                    }
                }
            }
        }

        GeyserApi.api().eventBus().register(this.hydraulic, new PackListener(this.hydraulic, this));

    }

    /**
     * Creates the pack for the given mod.
     *
     * @param mod the mod to create the pack for
     * @param packPath the path to the pack
     * @return {@code true} if the pack was created, {@code false} otherwise
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    boolean createPack(@NotNull ModInfo mod, @NotNull Path packPath) {
        PackConverter converter = new PackConverter()
                .logListener(new PackLogListener(LOGGER))
                .converters(createPackConverters())
                .output(packPath)
                .textureSubdirectory(mod.namespace());

        Map<Class<ConversionData>, List<ActionListener<ConversionData>>> actionListeners = new IdentityHashMap<>();
        for (PackModule<?> module : this.modules) {
            if (module instanceof ConvertablePackModule<?, ?> convertableModule) {
                actionListeners.computeIfAbsent((Class<ConversionData>) convertableModule.conversionType(),
                        e -> new ArrayList<>()).add((ConvertablePackModule<?, ConversionData>) convertableModule);
            }
        }

        converter.actionListeners(actionListeners);
        converter.postProcessor((javaPack, bedrockPack) -> {
            for (PackModule<?> module : this.modules) {
                PackPostProcessContext context = new PackPostProcessContext(this.hydraulic, mod, module, converter, javaPack, bedrockPack, packPath);
                if (!module.test(context)) {
                    continue;
                }

                module.postProcess0(context);
            }
        });

        try {
            for (final Path root : mod.roots()) {
                converter.input(root, false).convert();
            }
        } catch (IOException ex) {
            LOGGER.error("Failed to convert mod {} to pack", mod.id(), ex);
            return false;
        }

        // TODO Ignore packs if they only have a manifest

        // Now export the pack
        try {
            converter.pack();
        } catch (IOException ex) {
            LOGGER.error("Failed to export pack for mod {}", mod.id(), ex);
        }

        return true;
    }

    private List<? extends Converter<?>> createPackConverters() {
        return ServiceLoader.load(Converter.class)
            .stream()
            .map(ServiceLoader.Provider::get)
            .map(c -> (Converter<?>)c)
            .filter(Predicate.not(Converter::isExperimental))
            .map(converter ->
                converter instanceof ModelConverter && modelProvider != null
                    ? new CustomModelConverter(modelProvider)
                    : converter
            )
            .toList();
    }

    private void callEvents(@NotNull Event event) {
        for (ModInfo mod : this.hydraulic.mods()) {
            if (IGNORED_MODS.contains(mod.id())) {
                continue;
            }

            this.callEvent(mod, event);
        }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private void callEvent(@NotNull ModInfo mod, @NotNull Event event) {
        for (PackModule<?> module : this.modules) {
            module.call(event.getClass(), new PackEventContext(this.hydraulic, mod, module, event));
        }
    }

    private void initializeModLookups() {
        final Multimap<String, ModInfo> namespacesToMods = this.namespacesToMods;
        namespacesToMods.clear();
        for (final ModInfo mod : hydraulic.mods()) {
            for (final Path root : mod.roots()) {
                final Path assets = root.resolve("assets");
                if (!Files.isDirectory(assets)) continue;
                try (Stream<Path> stream = Files.list(assets)) {
                    IOStream.adapt(stream)
                        .filter(Files::isDirectory)
                        .unwrap()
                        .map(Path::getFileName)
                        .map(Path::toString)
                        .filter(namespace -> !namespace.equals("minecraft"))
                        .forEach(namespace -> namespacesToMods.put(namespace, mod));
                } catch (IOException e) {
                    LOGGER.error("Failed to list namespaces for mod {}", mod.id(), e);
                }
            }
        }

        final Multimap<String, ResourceLocation> modsToBlocks = this.modsToBlocks;
        modsToBlocks.clear();
        blocksLoop:
        for (final ResourceLocation block : BuiltInRegistries.BLOCK.keySet()) {
            if (block.getNamespace().equals("minecraft")) continue;
            for (final ModInfo mod : namespacesToMods.get(block.getNamespace())) {
                final Path checkFile = mod.resolveFile("assets/" + block.getNamespace() + "/blockstates/" + block.getPath() + ".json");
                if (checkFile != null) {
                    modsToBlocks.put(mod.id(), block);
                    continue blocksLoop;
                }
            }
        }

        final Multimap<String, ResourceLocation> modsToItems = this.modsToItems;
        modsToItems.clear();
        itemsLoop:
        for (final ResourceLocation item : BuiltInRegistries.ITEM.keySet()) {
            if (item.getNamespace().equals("minecraft")) continue;
            for (final ModInfo mod : namespacesToMods.get(item.getNamespace())) {
                final Path checkFile = mod.resolveFile("assets/" + item.getNamespace() + "/models/item/" + item.getPath() + ".json");
                if (checkFile != null) {
                    modsToItems.put(mod.id(), item);
                    continue itemsLoop;
                }
            }
        }
    }

    // Based off of ModelStitcher.vanillaProvider
    private static ModelStitcher.Provider createModelProvider(
        Collection<ModInfo> mods,
        Map<String, List<ResourcePack>> modPacks,
        LogListener log
    ) {
        final List<ResourcePack> flattenedPacks = mods.stream()
            .map(ModInfo::id)
            .map(modPacks::get)
            .flatMap(List::stream)
            .toList();

        Path vanillaPackPath = Paths.get("vanilla-pack.zip");
        VanillaPackProvider.create(vanillaPackPath, log);
        ResourcePack vanillaResourcePack = MinecraftResourcePackReader.minecraft().readFromZipFile(vanillaPackPath);

        return key -> {
            for (final ResourcePack pack : flattenedPacks) {
                final Model model = pack.model(key);
                if (model != null) {
                    return model;
                }
            }
            return vanillaResourcePack.model(key);
        };
    }

    public ListMultimap<String, ModInfo> getNamespacesToMods() {
        return namespacesToMods;
    }

    public ListMultimap<String, ResourceLocation> getModsToBlocks() {
        return modsToBlocks;
    }

    public ListMultimap<String, ResourceLocation> getModsToItems() {
        return modsToItems;
    }
}
