package com.k3jdr.mx.core.links;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.k3jdr.mx.core.Mx;

/**
 * One running copy of an app, as on macOS: a second launch — Windows opening an mx…:// link, or a double-clicked
 * document — hands its arguments to the running copy over a loopback socket and exits. The running copy listens on
 * a port written to {@code instance.port} in its workspace (the -data instance area, by default
 * %APPDATA%\MxAppsRCP\&lt;app&gt;\workspace), so one copy runs per workspace: a test launched with its own -data
 * neither hands over to nor takes over the copy in use.
 */
public final class SingleInstance {
    private SingleInstance() {}

    private static final String HELLO = "MXAPPS-ARGS";

    /**
     * Claims this app. Returns false when another copy is running and took {@code args}; the caller should then
     * exit. Otherwise starts listening and hands later launches' arguments to {@code onArgs} (on a background
     * thread: switch to the UI thread there).
     */
    public static boolean claim(String app, String[] args, Consumer<List<String>> onArgs) {
        Path portFile = instanceFolder(app).resolve("instance.port");
        if (handOff(portFile, args)) return false;
        try {
            ServerSocket server = new ServerSocket();
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            Files.writeString(portFile, Integer.toString(server.getLocalPort()));
            Thread t = new Thread(() -> listen(server, onArgs), app + " instance listener");
            t.setDaemon(true);
            t.start();
        } catch (IOException e) {
            // without the listener every launch opens its own copy, which still works
        }
        return true;
    }

    /** The workspace (instance area) when there is one, else %APPDATA%\MxAppsRCP\&lt;app&gt;. */
    private static Path instanceFolder(String app) {
        try {
            var location = org.eclipse.core.runtime.Platform.getInstanceLocation();
            if (location != null && location.isSet() && "file".equals(location.getURL().getProtocol())) {
                // URIUtil copes with the unencoded URLs Equinox makes (a space in the folder name).
                Path dir = Path.of(org.eclipse.core.runtime.URIUtil.toURI(location.getURL()));
                Files.createDirectories(dir);
                return dir;
            }
        } catch (Exception | LinkageError e) {
            // no OSGi instance area (a plain Java test): fall back to the app's folder
        }
        return Mx.appData(app);
    }

    private static boolean handOff(Path portFile, String[] args) {
        if (!Files.isRegularFile(portFile)) return false;
        try {
            int port = Integer.parseInt(Files.readString(portFile).trim());
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 700);
                s.setSoTimeout(1500);
                PrintWriter out = new PrintWriter(s.getOutputStream(), true, StandardCharsets.UTF_8);
                out.println(HELLO);
                if (args != null) for (String a : args) out.println(a.replace('\n', ' '));
                out.println();
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                return "OK".equals(in.readLine());
            }
        } catch (IOException | NumberFormatException e) {
            return false;
        }
    }

    private static void listen(ServerSocket server, Consumer<List<String>> onArgs) {
        while (!server.isClosed()) {
            try (Socket s = server.accept()) {
                s.setSoTimeout(2000);
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                if (!HELLO.equals(in.readLine())) continue;
                List<String> args = new ArrayList<>();
                for (String line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine()) args.add(line);
                new PrintWriter(s.getOutputStream(), true, StandardCharsets.UTF_8).println("OK");
                onArgs.accept(args);
            } catch (IOException e) {
                // a bad caller; keep listening
            }
        }
    }
}
