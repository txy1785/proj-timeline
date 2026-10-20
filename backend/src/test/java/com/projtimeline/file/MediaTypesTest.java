package com.projtimeline.file;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MediaTypesTest {
    @Test
    void derivesTypeFromExtensionInsteadOfClientClaim() {
        assertThat(MediaTypes.of("pdf")).isEqualTo("application/pdf");
        assertThat(MediaTypes.of("PNG")).isEqualTo("image/png");
        assertThat(MediaTypes.of("unknown-ext")).isEqualTo(MediaTypes.DEFAULT);
        assertThat(MediaTypes.of(null)).isEqualTo(MediaTypes.DEFAULT);
    }

    @Test
    void onlyImagesAndPdfAreInlinePreviewable() {
        assertThat(MediaTypes.inlineAllowed(MediaTypes.of("pdf"))).isTrue();
        assertThat(MediaTypes.inlineAllowed(MediaTypes.of("jpg"))).isTrue();
        // SVG 与文本可以携带脚本，只允许下载
        assertThat(MediaTypes.inlineAllowed(MediaTypes.of("svg"))).isFalse();
        assertThat(MediaTypes.inlineAllowed(MediaTypes.of("txt"))).isFalse();
        assertThat(MediaTypes.inlineAllowed(MediaTypes.of("md"))).isFalse();
        assertThat(MediaTypes.inlineAllowed("text/html")).isFalse();
        assertThat(MediaTypes.inlineAllowed(null)).isFalse();
    }
}
