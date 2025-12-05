package open.repocraft.server.infra.git.service;

public final class GitCommandParser {
    private GitCommandParser() {
    }

    public static GitRequest parse(String command) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("Empty command");
        }

        String trimmed = command.strip();
        String[] parts = trimmed.split("\\s+", 2);
        if (parts.length < 2) {
            throw new IllegalArgumentException("Invalid command: " + command);
        }

        GitService service = switch (parts[0]) {
            case "git-upload-pack" -> GitService.UPLOAD_PACK;
            case "git-receive-pack" -> GitService.RECEIVE_PACK;
            default -> throw new IllegalArgumentException("Unsupported service: " + parts[0]);
        };

        String repoPath = stripQuotes(parts[1]);
        GitRequest request = new GitRequest(service, repoPath, null);
        request.validate();
        return request;
    }

    private static String stripQuotes(String value) {
        if (value == null || value.length() < 2) {
            return value;
        }
        if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
