package open.repocraft.server.infra.git.service;

public record GitRequest(
        GitService service,
        String repoPath,
        String protocolVersion
) {
    public void validate() {
        if (service == null) {
            throw new IllegalArgumentException("Service is required");
        }
        if (repoPath == null || repoPath.isBlank()) {
            throw new IllegalArgumentException("Repository path is required");
        }
    }
}
