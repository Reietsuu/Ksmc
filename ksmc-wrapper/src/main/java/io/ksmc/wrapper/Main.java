package io.ksmc.wrapper;

/**
 * Point d'entrée du wrapper Ksmc, présent dans les images runtime.
 *
 * <ul>
 *   <li>{@code prepare} : initContainer — récupère logiciel, templates et applique la configuration réseau ;</li>
 *   <li>{@code run} : conteneur principal — lance et supervise le serveur Minecraft ;</li>
 *   <li>{@code build} : Job de construction — BuildTools / installeurs, résultat téléversé au controller.</li>
 * </ul>
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        String command = args.length == 0 ? "" : args[0];
        WrapperEnv env = WrapperEnv.fromSystem();
        int code;
        if ("prepare".equals(command)) {
            code = new Preparer(env, new InternalClient(env)).prepare();
        } else if ("run".equals(command)) {
            code = new Runner(env).run();
        } else if ("build".equals(command)) {
            code = new Builder(env, new InternalClient(env)).build();
        } else {
            System.err.println("usage: ksmc-wrapper prepare|run|build");
            code = 2;
        }
        System.exit(code);
    }
}
