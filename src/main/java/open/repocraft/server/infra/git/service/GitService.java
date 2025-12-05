package open.repocraft.server.infra.git.service;

public enum GitService {
    UPLOAD_PACK("git-upload-pack"),
    RECEIVE_PACK("git-receive-pack");

    private final String command;

    GitService(String command) {
        this.command = command;
    }

    public String command() {
        return command;
    }
}
