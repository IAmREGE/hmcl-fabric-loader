package rege.rege.hmclmods.loader.game.bootstrap;

public final class Dependency {
    public final String groupId;
    public final String artifactId;
    public final String version;
    public final String expectedSha1;
    public final String primaryRepo;

    public Dependency(
        String groupId, String artifactId, String version, String expectedSha1,
        String primaryRepo
    ) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
        this.expectedSha1 = expectedSha1;
        this.primaryRepo = primaryRepo;
    }

    public String getRelativePath() {
        return this.groupId.replace('.', '/') + '/' + this.artifactId + '/' +
               this.version + '/' + this.artifactId + '-' + this.version +
               ".jar";
    }

    public String getFileName() {
        return this.artifactId + '-' + this.version + ".jar";
    }
}
