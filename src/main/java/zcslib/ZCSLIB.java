package zcslib;

import zcslib.kernel.ZCSKernel;
import zcslib.log.CrashHandler;
import zcslib.log.LogRotator;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

@Mod(ZCSLIB.MOD_ID)
public class ZCSLIB {
    public static final String MOD_ID = "zcslib";
    public static final String VERSION = "0.2.0";
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Global kernel instance, initialized once at mod construction. */
    private static ZCSKernel kernel;

    public ZCSLIB(IEventBus modBus, ModContainer container) {
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
        LOGGER.info("  #  ZCSLIB                                  v{}", VERSION);
        LOGGER.info("  #  我是简介");
        LOGGER.info("  #  反正也没人看我");
        LOGGER.info("  #  总之看到我就是加载成功了");
        LOGGER.info("  #=============================================================#");
        LOGGER.info("");

        LOGGER.info("ZCSLIB Kernel v{} initializing...", VERSION);

        // Phase 10: Install crash handler + run log rotation
        CrashHandler.install();
        LogRotator.rotateAll();

        kernel = new ZCSKernel(FMLPaths.GAMEDIR.get());
        LOGGER.info("ZCSLIB Kernel v{} initialized — {} plugin(s) online.",
                VERSION, kernel.getPluginCount());
    }

    public static ZCSKernel getKernel() {
        return kernel;
    }
}
