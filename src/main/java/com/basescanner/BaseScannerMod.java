package com.basescanner;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

@Mod(
    modid = BaseScannerMod.MODID,
    name = "Base Scanner",
    version = "1.0",
    acceptedMinecraftVersions = "[1.8.9]",
    clientSideOnly = true
)
public class BaseScannerMod {

    public static final String MODID = "basescanner";
    public static final String NAME = "Base Scanner";
    public static final String VERSION = "1.0";

    public static BaseScannerMod instance;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        instance = this;
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        // Register the main controller as an event listener
        MinecraftForge.EVENT_BUS.register(new ScannerController());
    }
}
