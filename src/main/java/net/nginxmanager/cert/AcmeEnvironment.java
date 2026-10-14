package net.nginxmanager.cert;

public enum AcmeEnvironment {
    PRODUCTION("acme://letsencrypt.org"),
    STAGING("acme://letsencrypt.org/staging");

    private final String directory;

    AcmeEnvironment(String directory) {
        this.directory = directory;
    }

    public String directory() {
        return directory;
    }
}
