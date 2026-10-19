package io.ksmc.api.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class VersionRangeTest {

    static boolean m(String range, String version) {
        return VersionRange.parse(range).matches(SemVer.parse(version));
    }

    @Test
    void semVerOrdering() {
        assertThat(SemVer.parse("1.10.0")).isGreaterThan(SemVer.parse("1.9.9"));
        assertThat(SemVer.parse("1.0.0-alpha")).isLessThan(SemVer.parse("1.0.0"));
        assertThat(SemVer.parse("1.0.0-alpha.2")).isLessThan(SemVer.parse("1.0.0-alpha.10"));
        assertThat(SemVer.parse("1.0.0+build.5")).isEqualByComparingTo(SemVer.parse("1.0.0"));
        assertThat(SemVer.parse("2")).isEqualTo(new SemVer(2, 0, 0, null));
        assertThatThrownBy(() -> SemVer.parse("abc")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ranges() {
        assertThat(m("*", "9.9.9")).isTrue();
        assertThat(m("^1.2", "1.9.0")).isTrue();
        assertThat(m("^1.2", "2.0.0")).isFalse();
        assertThat(m("^1.2", "1.1.0")).isFalse();
        assertThat(m("^0.3.1", "0.3.5")).isTrue();
        assertThat(m("^0.3.1", "0.4.0")).isFalse();
        assertThat(m("~1.2.3", "1.2.9")).isTrue();
        assertThat(m("~1.2.3", "1.3.0")).isFalse();
        assertThat(m("1.x", "1.5.0")).isTrue();
        assertThat(m("1.x", "2.0.0")).isFalse();
        assertThat(m("1.2", "1.2.7")).isTrue();
        assertThat(m("1.2.3", "1.2.3")).isTrue();
        assertThat(m("1.2.3", "1.2.4")).isFalse();
        assertThat(m(">=1.0 <1.5", "1.4.9")).isTrue();
        assertThat(m(">=1.0 <1.5", "1.5.0")).isFalse();
        assertThat(m(null, "0.0.1")).isTrue();
    }
}
