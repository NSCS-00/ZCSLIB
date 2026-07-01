package zcslib;

import zcslib.kernel.ZCSKernel;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/**
 * NeoForge @Mod entry point. Thin shell — all logic delegates to {@link ZCSLIBCommon}.
 *
 * <p>Compiled against NeoForge 21.1.
 * Expected to run on 21.1 and 26.1 without recompilation:
 * the framework APIs used here (@Mod, IEventBus, standard server events)
 * are NeoForge's public contract across major versions.
 */
@Mod(ZCSLIBCommon.MOD_ID)
public class ZCSLIB {
    public static final String MOD_ID = ZCSLIBCommon.MOD_ID;
    public static final String VERSION = ZCSLIBCommon.VERSION;
    private static final Logger LOGGER = LogUtils.getLogger();

    public ZCSLIB(IEventBus modBus, ModContainer container) {
        splash();
        ZCSLIBCommon.bootstrap();

        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
    }

    private void onServerStarted(ServerStartedEvent event) {
        var server = event.getServer();
        ZCSLIBCommon.getKernel().initMcPort(server,
                server.getCommands().getDispatcher());
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
    }

    private void onServerTick(ServerTickEvent.Post event) {
        ZCSLIBCommon.onTick();
    }

    private void onServerStopping(ServerStoppingEvent event) {
        ZCSLIBCommon.shutdown();
    }

    /** Backward-compatible accessor. Delegates to {@link ZCSLIBCommon#getKernel()}. */
    public static ZCSKernel getKernel() {
        return ZCSLIBCommon.getKernel();
    }

    // ── Splash ──────────────────────────────────────────────

    private static void splash() {
        LOGGER.info("");
        LOGGER.info("     .:=-=--\".\"");
        LOGGER.info("  .-=+==========*:");
        LOGGER.info("        .      .:+#**+++==--::..    ");
        LOGGER.info("       :::..:-=++**########*****##+-=:.   ");
        LOGGER.info("   :---------=--=*####################*++=+*:   ");
        LOGGER.info(" +***+++=:---=*########*********++==--:::-=**#*-=::.   ");
        LOGGER.info("  ==:    .=+*##%@@@@%#*++==--===----::...    -##%%%%%*+=-. ");
        LOGGER.info("       :+#@@@%@@@@@@@@@@@@@@@%%#*+=--::..  :=%@@@@@@@@%#-: ");
        LOGGER.info("       ::-+#@@@@@@@@%#*+==-::--===----..   .#@@@@@%%##*=: ");
        LOGGER.info("   ..   .:-=*%@@@@@%#*+=-:......::..     -#@@@@@%#+-:. ");
        LOGGER.info("          ...::--=++====--::...          .=#@@@@@%+-:.   ");
        LOGGER.info("                                       .  .*@@@@%#*+-:.   ");
        LOGGER.info("                                    #@%%%@@@@%#*+=-:...   ");
        LOGGER.info("                                   :%@@%@@@@@@@@@%#*+++=-::");
        LOGGER.info("                                  . .-:=*@@@@@@%####*******#");
        LOGGER.info("                                   ..---=*%@@@@%*++++======:");
        LOGGER.info("                                   ......:=+@@@@#*++++===--:");
        LOGGER.info("                                   ........:*%@@%#*++==---::");
        LOGGER.info("              .=+=====---+======-::.........:=%@@%#*++===>>>");
        LOGGER.info("             :==::::--------------------::::::::--=++*+++++=*");
        LOGGER.info("          .:--:..      ..:--------------------::..   ..:=+**#");
        LOGGER.info("          =-:.    .........:===================------:.    .:+");
        LOGGER.info("         .  .:----:::::-----==================-----------:.");
        LOGGER.info("");
        LOGGER.info("  #=============================================================#");
        LOGGER.info("  #  ZCSLIB                                  v{}", ZCSLIBCommon.VERSION);
        LOGGER.info("  #  我是简介");
        LOGGER.info("  #  反正也没人看我");
        LOGGER.info("  #  总之看到我就是加载成功了");
        LOGGER.info("  #=============================================================#");
        LOGGER.info("");
    }
}
