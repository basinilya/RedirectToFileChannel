package snippet;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.ProcessBuilder.Redirect;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;

public class RedirectToFileChannel {

    private static final String FAKESHELL = "fakeshell";

    /**
     * This obviously is needed in java: redirect process I/O to an already opened File Channel.
     * 
     * @param fileChannel
     * @return
     */
    private static java.lang.ProcessBuilder.Redirect redirectToFileChannel(
            final FileChannel fileChannel) {
        final FileDescriptor fd = ReflectUtil.getFileDescriptor(fileChannel);
        return ReflectUtil.newRedirectPipe(fd);
    }

    public static void main(final String[] args) throws Exception {
        if (args.length > 0 && FAKESHELL.equals(args[0])) {
            fakeshell();
            return;
        }

        log(
            "This program will start a new process with output redirected to a temporary "
                + "file, then, while the process is still live, the temporary file will be renamed "
                + "to contain the PID in its name.");
        log(
            "Redirecting native standard I/O helps troubleshoot when a log framework can't print crash logs.");
        log(
            "And redirecting to an already opened File Channel allows log rotation on Windows (to some degree).");
        log(
            "The program uses illegal reflective access, don't foget to pass the necessary VM options:");
        log("--add-opens=java.base/sun.nio.ch=ALL-UNNAMED");
        log("--add-opens=java.base/java.lang=ALL-UNNAMED");
        log("");

        final File logDir = new File("target/log");
        logDir.mkdirs();
        final Path initialLogPath = Files.createTempFile(logDir.toPath(), ".forked", ".log");
        log("created tmp file: " + initialLogPath.toAbsolutePath());
        try (final FileChannel movableFileChannel = openMovableFileChannel(initialLogPath);) {
            log("opened tmp file for writing");
            final OutputStream tmpOut = Channels.newOutputStream(movableFileChannel);
            final String messageFromParent =
                "This message was appended by the parent process; below is the output of the child process";
            tmpOut.write((messageFromParent + "\r\n").getBytes());
            tmpOut.flush();

            final boolean closeChannel = true;
            final Path finalLogPath =
                runProgramWithRedirectedOutput(movableFileChannel, initialLogPath, closeChannel);

            // On WSL2 mounted host FS read may fail if the file is not closed
            log("Log file contents:");
            log("==================");
            Files.copy(finalLogPath, System.out);
            log("");
            log("==================");
        }
    }

    private static void fakeshell() throws IOException {
        System.out.println("fake shell");
        final BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while (null != (line = in.readLine())) {
            final String command = line.replaceFirst(">&2", "");
            @SuppressWarnings("resource")
            final PrintStream out = command.equals(line) ? System.out : System.err;
            final String args = command.replaceFirst("^echo ", "");
            if (args.equals(command)) {
                System.err.println("unknown command: " + command);
            } else {
                out.println(args);
            }
        }
    }

    private static final String LS = System.lineSeparator();

    private static FileChannel openMovableFileChannel(final Path initialLogPath)
                                                                                 throws IOException {
        return (FileChannel) Files
            .newByteChannel(
                initialLogPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    private static Path runProgramWithRedirectedOutput(
            final FileChannel movableFileChannel,
            final Path initialLogPath,
            final boolean closeChannel) throws IOException, InterruptedException {
        final String[] argv = getArgv();
        final String argv0 = argv[0].replaceFirst(".*[/\\\\]", "");
        final Path finalLogPath;
        final Process p = startProcessRedirectToFileChannel(movableFileChannel, argv);
        try {
            try (final OutputStream os = p.getOutputStream();) {
                final long pid = p.pid();
                log("process '" + argv0 + "' started (PID=" + pid + ")");
                finalLogPath = renameLogFile(initialLogPath, pid);
                if (closeChannel) {
                    movableFileChannel.close();
                }
                sendCommands(os);
            }
            log("sent EOF");
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                throw new IOException("Timeout waiting for the process: " + argv0);
            }
            if (p.exitValue() != 0) {
                log(argv0 + " exited with nonzero exit code: " + p.exitValue());
            } else {
                log(argv0 + " exited with exit code 0");
            }
            return finalLogPath;
        } catch (final Throwable e) {
            try {
                p.destroyForcibly();
                p.exitValue();
            } catch (final RuntimeException e2) {
                e.addSuppressed(e2);
            }
            throw e;
        }
    }

    private static void sendCommands(final OutputStream os) throws InterruptedException {
        try {
            sendCommand(os, "echo this should go to stdout");
            Thread.sleep(5_000);
            sendCommand(os, "echo this should go to stderr>&2");
            Thread.sleep(5_000);
            os.close();
        } catch (final IOException e) {
            log(e.toString());
        }
    }

    private static String[] getArgv() {
        return new String[] {
            System.getProperty("java.home") + "/bin/java",
            "-cp",
            System.getProperty("java.class.path"),
            RedirectToFileChannel.class.getName(),
            FAKESHELL };
    }

    private static void sendCommand(final OutputStream os, final String command)
                                                                                 throws IOException {
        os.write((command + LS).getBytes());
        log("sent command: " + command);
    }

    private static Process startProcessRedirectToFileChannel(
            final FileChannel fileChannel,
            final String... args) throws IOException {
        log("starting command:");
        for (final String arg : args) {
            System.out.print("\"");
            System.out.print(arg);
            System.out.print("\" ");
        }
        log("");
        log("");
        final Redirect redirectPipe = redirectToFileChannel(fileChannel);
        final ProcessBuilder pb = new ProcessBuilder(args);

        pb.redirectOutput(redirectPipe);
        pb.redirectErrorStream(true);
        return pb.start();
    }

    private static Path renameLogFile(final Path initialLogPath, final long pid)
                                                                                 throws IOException {
        final Path finalLogPath = initialLogPath.resolveSibling("forked-" + pid + ".log");
        Files.move(initialLogPath, finalLogPath, StandardCopyOption.REPLACE_EXISTING);
        log("log file renamed to: " + finalLogPath.toAbsolutePath());
        return finalLogPath;
    }

    private static void log(final String string) {
        System.out.println(string);
    }

    private static class ReflectUtil {

        private static final Class<?> C_REDIRECT_PIPE_IMPL =
            getClazz("java.lang.ProcessBuilder$RedirectPipeImpl");

        private static final Class<?> C_FILE_CHANNEL_IMPL = getClazz("sun.nio.ch.FileChannelImpl");

        private static final Field F_FILE_CHANNEL_IMPL_FD = getField(C_FILE_CHANNEL_IMPL, "fd");

        private static final Constructor<?> REDIRECT_PIPE_IMPL_CONSTR =
            getConstructor(C_REDIRECT_PIPE_IMPL);

        private static final Field F_REDIRECT_PIPE_IMPL_FD = getField(C_REDIRECT_PIPE_IMPL, "fd");

        private static FileDescriptor getFileDescriptor(final FileChannel fileChannel) {
            try {
                return (FileDescriptor) F_FILE_CHANNEL_IMPL_FD.get(fileChannel);
            } catch (final IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }

        private static Redirect newRedirectPipe(final FileDescriptor fd) {
            try {
                final Redirect redirectPipe = (Redirect) REDIRECT_PIPE_IMPL_CONSTR.newInstance();
                F_REDIRECT_PIPE_IMPL_FD.set(redirectPipe, fd);
                return redirectPipe;
            } catch (
                InstantiationException
                | IllegalAccessException
                | InvocationTargetException e) {
                throw new RuntimeException(e);
            }
        }

        private static Constructor<?> getConstructor(
                final Class<?> clazz,
                final Class<?>... parameterTypes) {
            Constructor<?> constr;
            try {
                constr = clazz.getDeclaredConstructor(parameterTypes);
                constr.setAccessible(true);
                return constr;
            } catch (final NoSuchMethodException e) {
                throw new RuntimeException(e);
            }
        }

        private static Field getField(final Class<?> class1, final String fieldName) {
            try {
                final Field fHandler = class1.getDeclaredField(fieldName);
                fHandler.setAccessible(true);
                return fHandler;
            } catch (final NoSuchFieldException e) {
                throw new RuntimeException(e);
            }
        }

        private static Class<?> getClazz(final String className) {
            try {
                return Class.forName(className);
            } catch (final ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
