package museon_online.astor_butler.api.glasses.tasks;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static museon_online.astor_butler.api.glasses.tasks.InMemoryStaffEvidenceStorage.heic;
import static museon_online.astor_butler.api.glasses.tasks.InMemoryStaffEvidenceStorage.isoMedia;
import static museon_online.astor_butler.api.glasses.tasks.InMemoryStaffEvidenceStorage.jpeg;
import static museon_online.astor_butler.api.glasses.tasks.InMemoryStaffEvidenceStorage.png;
import static museon_online.astor_butler.api.glasses.tasks.InMemoryStaffEvidenceStorage.webp;
import static org.assertj.core.api.Assertions.assertThat;

/** A photo is what its bytes say, not what its name or its header says. */
class StaffEvidenceImagesTest {

    @Test void phonePhotosAreKnownByTheirFirstBytes() {
        assertThat(StaffEvidenceImages.detect(jpeg(64))).isEqualTo("image/jpeg");
        assertThat(StaffEvidenceImages.detect(png())).isEqualTo("image/png");
        assertThat(StaffEvidenceImages.detect(webp())).isEqualTo("image/webp");
        assertThat(StaffEvidenceImages.detect(heic())).isEqualTo("image/heic");
        assertThat(StaffEvidenceImages.detect(isoMedia("mif1"))).isEqualTo("image/heif");
    }

    @Test void otherFilesAreNotPhotosWhateverTheyAreCalled() {
        for (String text : new String[]{"plain text pretending to be a photo", "GIF89a------------------------",
                "%PDF-1.7 ---------------------", "<svg xmlns=\"http://www.w3.org/2000/svg\"/>", "RIFF----WAVEfmt --------------",
                "PK\u0003\u0004 a zip archive ----------"}) {
            assertThat(StaffEvidenceImages.detect(text.getBytes(StandardCharsets.ISO_8859_1))).as(text).isNull();
        }
        // The same container as HEIC holds video and AVIF: only a still HEIF brand is a photo here.
        assertThat(StaffEvidenceImages.detect(isoMedia("isom"))).isNull();
        assertThat(StaffEvidenceImages.detect(isoMedia("mp42"))).isNull();
        assertThat(StaffEvidenceImages.detect(isoMedia("avif"))).isNull();
        assertThat(StaffEvidenceImages.detect(new byte[0])).isNull();
        assertThat(StaffEvidenceImages.detect(null)).isNull();
        assertThat(StaffEvidenceImages.detect(new byte[]{(byte) 0xff, (byte) 0xd8})).as("a cut-off header").isNull();
        assertThat(StaffEvidenceImages.detect(Arrays.copyOf(png(), 12))).as("a signature without its first chunk").isNull();
        byte[] lying = heic();
        lying[3] = 127;
        assertThat(StaffEvidenceImages.detect(Arrays.copyOf(lying, 32))).as("a box longer than its file").isNull();
    }

    @Test void theDeclaredTypeMustBeTheRealOne() {
        assertThat(StaffEvidenceImages.matches("image/jpeg", jpeg(64))).isTrue();
        assertThat(StaffEvidenceImages.matches("image/png", jpeg(64))).isFalse();
        assertThat(StaffEvidenceImages.matches("image/jpeg", png())).isFalse();
        assertThat(StaffEvidenceImages.matches("image/jpeg", "text".getBytes(StandardCharsets.UTF_8))).isFalse();
        assertThat(StaffEvidenceImages.matches(null, jpeg(64))).isFalse();
        // HEIC and HEIF are one container, and phones name it either way.
        assertThat(StaffEvidenceImages.matches("image/heif", heic())).isTrue();
        assertThat(StaffEvidenceImages.matches("image/heic", isoMedia("mif1"))).isTrue();
        assertThat(StaffEvidenceImages.matches("image/webp", heic())).isFalse();
    }

    @Test void everyAcceptedTypeHasItsOwnFileExtension() {
        assertThat(StaffEvidenceImages.TYPES).extracting(StaffEvidenceImages::extension)
                .containsExactlyInAnyOrder("jpg", "png", "webp", "heic", "heif");
        assertThat(StaffEvidenceImages.extension("text/html")).isEqualTo("bin");
    }
}
