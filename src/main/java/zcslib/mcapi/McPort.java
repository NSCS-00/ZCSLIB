package zcslib.mcapi;

import net.minecraft.server.MinecraftServer;

/**
 * DreamWorker-exclusive gate to Minecraft internals.
 *
 * <p>Triple security:
 * <ol>
 * <li><b>Compile-time:</b> package-private constructor — plugins cannot {@code new} it.</li>
 * <li><b>Runtime:</b> {@link #open()} verifies caller == {@code DreamWorker} via StackWalker.</li>
 * <li><b>Class-loading:</b> PluginClassLoader denies {@code zcslib.mcapi.*} imports.</li>
 * </ol>
 *
 * <p>All returned data is snapshots or read-only views. No raw MC objects
 * cross the API boundary.
 */
public final class McPort {

    private static McPort instance;

    private final WorldAPI worldAPI;
    private final PlayerAPI playerAPI;
    private final TickAPI tickAPI;
    private final BlockAPI blockAPI;

    /** Package-private: only ZCSKernel can construct. */
    McPort(MinecraftServer server) {
        this.worldAPI = new WorldAPI(server);
        this.playerAPI = new PlayerAPI(server);
        this.tickAPI = new TickAPI(server);
        this.blockAPI = new BlockAPI(server);
    }

    // ── Static init / access ───────────────────────────────

    /** Called once by ZCSKernel when the server starts. Returns the instance. */
    public static McPort init(MinecraftServer server) {
        instance = new McPort(server);
        return instance;
    }

    /**
     * Open the MC port. Only DreamWorker can call this.
     *
     * @throws SecurityException if the caller is not {@code zcslib.daemon.DreamWorker}
     * @throws IllegalStateException if McPort has not been initialised
     */
    public static McPort open() {
        Class<?> caller = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                .getCallerClass();
        if (!caller.getName().equals("zcslib.daemon.DreamWorker")) {
            throw new SecurityException("McPort reserved for DreamWorker — caller was " + caller.getName());
        }
        if (instance == null) {
            throw new IllegalStateException("McPort not initialised — wait for server start");
        }
        return instance;
    }

    // ── Sub-API accessors ──────────────────────────────────

    public WorldAPI world()   { return worldAPI; }
    public PlayerAPI players() { return playerAPI; }
    public TickAPI tick()     { return tickAPI; }
    public BlockAPI blocks()  { return blockAPI; }

    /** Call every server tick (post) for MSPT sampling. */
    public void fireTickEnd() {
        tickAPI.invokeTickEnd();
    }
}
