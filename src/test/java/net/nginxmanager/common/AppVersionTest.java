package net.nginxmanager.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AppVersionTest {

    @Test
    void releaseLinksToItsOwnNotes() {
        assertThat(AppVersion.label("1.0.0")).isEqualTo("v1.0.0");
        assertThat(AppVersion.releaseNotesUrl("1.0.0")).isEqualTo(AppVersion.RELEASE_NOTES_BASE + "v1.0.0.md");
        assertThat(AppVersion.releaseNotesUrl("v1.0.0")).isEqualTo(AppVersion.RELEASE_NOTES_BASE + "v1.0.0.md");
    }

    @Test
    void developmentBuildsAndTestTagsLinkToTheReleaseList() {
        assertThat(AppVersion.label("dev")).isEqualTo("dev");
        assertThat(AppVersion.label(null)).isEqualTo("dev");
        assertThat(AppVersion.releaseNotesUrl("dev")).endsWith("/releases/README.md");
        assertThat(AppVersion.label("1.1.0-rc1")).isEqualTo("v1.1.0-rc1");
        assertThat(AppVersion.releaseNotesUrl("1.1.0-rc1")).endsWith("/releases/README.md");
    }
}
