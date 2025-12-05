package open.repocraft.server.infra.git.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

public class ProcessGitCommandExecutor implements GitCommandExecutor {
    private final String uploadPackPath;
    private final String receivePackPath;
    private final Map<String, String> baseEnv;
    private final Path workDir;

    public ProcessGitCommandExecutor() {
        this(null, null, Map.of(), null);
    }

    public ProcessGitCommandExecutor(String uploadPackPath,
                                     String receivePackPath,
                                     Map<String, String> baseEnv,
                                     Path workDir) {
        this.uploadPackPath = uploadPackPath;
        this.receivePackPath = receivePackPath;
        this.baseEnv = baseEnv == null ? Map.of() : Map.copyOf(baseEnv);
        this.workDir = workDir;
    }

    @Override
    public void execute(GitRequest request, InputStream stdin, OutputStream stdout, OutputStream stderr)
            throws IOException, InterruptedException {
        Objects.requireNonNull(request, "request");
        request.validate();
        InputStream safeIn = stdin == null ? InputStream.nullInputStream() : stdin;
        OutputStream safeOut = stdout == null ? OutputStream.nullOutputStream() : stdout;
        OutputStream safeErr = stderr == null ? OutputStream.nullOutputStream() : stderr;

        String binary = resolveBinary(request.service());
        ProcessBuilder builder = new ProcessBuilder(binary, request.repoPath());
        if (workDir != null) {
            builder.directory(workDir.toFile());
        }

        Map<String, String> env = builder.environment();
        env.putAll(baseEnv);
        if (request.protocolVersion() != null && !request.protocolVersion().isBlank()) {
            env.put("GIT_PROTOCOL", request.protocolVersion());
        }

        Process process = builder.start();
        try (OutputStream processIn = process.getOutputStream();
             InputStream processOut = process.getInputStream();
             InputStream processErr = process.getErrorStream()) {

            Thread stdinPump = pump(safeIn, processIn, true);
            Thread stdoutPump = pump(processOut, safeOut, false);
            Thread stderrPump = pump(processErr, safeErr, false);

            int exit = process.waitFor();
            stdinPump.join();
            stdoutPump.join();
            stderrPump.join();

            if (exit != 0) {
                throw new IOException("git command exited with code " + exit);
            }
        }
    }

    private String resolveBinary(GitService service) {
        return switch (service) {
            case UPLOAD_PACK -> uploadPackPath != null ? uploadPackPath : service.command();
            case RECEIVE_PACK -> receivePackPath != null ? receivePackPath : service.command();
        };
    }

    private Thread pump(InputStream in, OutputStream out, boolean closeOut) {
        Thread thread = new Thread(() -> {
            try {
                in.transferTo(out);
                if (closeOut) {
                    out.close();
                } else {
                    out.flush();
                }
            } catch (IOException ignored) {
                // Suppress to avoid masking exit code; caller decides how to handle I/O errors.
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
}
