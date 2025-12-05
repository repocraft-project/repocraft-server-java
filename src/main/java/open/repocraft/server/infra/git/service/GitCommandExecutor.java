package open.repocraft.server.infra.git.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public interface GitCommandExecutor {
    /**
     * Execute the git service for the given request (upload-pack/receive-pack).
     *
     * @param request git service request
     * @param stdin   data from the SSH channel
     * @param stdout  data to the SSH channel
     * @param stderr  stderr to the SSH channel
     */
    void execute(GitRequest request, InputStream stdin, OutputStream stdout, OutputStream stderr)
            throws IOException, InterruptedException;
}
