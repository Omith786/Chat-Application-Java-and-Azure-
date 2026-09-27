package io.github.omith786.chat.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ChatRulesTest {

    @Test
    void usernamesAreTrimmedAndLowerCased() {
        assertThat(ChatRules.normaliseUsername("  Alice_99 ")).isEqualTo("alice_99");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "ab", "this-name-is-far-too-long", "has space", "émile", "a:b", "system", "Admin"})
    void rejectsBadUsernames(String raw) {
        assertThatIllegalArgumentException().isThrownBy(() -> ChatRules.normaliseUsername(raw));
    }

    @Test
    void rejectsNullUsername() {
        assertThatIllegalArgumentException().isThrownBy(() -> ChatRules.normaliseUsername(null));
    }

    @Test
    void validUsernameCheckRequiresCanonicalForm() {
        assertThat(ChatRules.isValidUsername("alice")).isTrue();
        assertThat(ChatRules.isValidUsername("Alice")).isFalse();
        assertThat(ChatRules.isValidUsername("system")).isFalse();
        assertThat(ChatRules.isValidUsername(null)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
            "general, true",
            "java-azure, true",
            "a1, true",
            "-leading, false",
            "trailing-, false",
            "Upper, false",
            "'', false",
            "abcdefghijabcdefghijabcdefghijabc, false"
    })
    void roomIds(String id, boolean valid) {
        assertThat(ChatRules.isValidRoomId(id)).isEqualTo(valid);
    }

    @Test
    void contentIsCleanedButKeepsNewlinesAndTabs() {
        String cleaned = ChatRules.normaliseContent("  line one\r\nline\ttwo\u0007‮  ");
        assertThat(cleaned).isEqualTo("line one\nline\ttwo");
    }

    @Test
    void contentIsNfcNormalised() {
        String decomposed = "café";
        assertThat(ChatRules.normaliseContent(decomposed)).isEqualTo("café");
    }

    @Test
    void emptyOrInvisibleContentIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> ChatRules.normaliseContent(null));
        assertThatIllegalArgumentException().isThrownBy(() -> ChatRules.normaliseContent(" \n\t "));
        assertThatIllegalArgumentException().isThrownBy(() -> ChatRules.normaliseContent("​\u0000"));
    }

    @Test
    void contentLengthIsCountedInCodePoints() {
        String emoji = "😀"; // one code point, two UTF-16 chars
        String atLimit = emoji.repeat(ChatRules.MAX_MESSAGE_LENGTH);
        assertThat(ChatRules.normaliseContent(atLimit)).isEqualTo(atLimit);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ChatRules.normaliseContent("x".repeat(ChatRules.MAX_MESSAGE_LENGTH + 1)));
    }

    @Test
    void roomNamesAreSingleLineAndBounded() {
        assertThat(ChatRules.normaliseRoomName("  Java \n  and   Azure ")).isEqualTo("Java and Azure");
        assertThatIllegalArgumentException().isThrownBy(() -> ChatRules.normaliseRoomName("x"));
        assertThatIllegalArgumentException().isThrownBy(() -> ChatRules.normaliseRoomName("x".repeat(41)));
        assertThat(ChatRules.normaliseRoomDescription(null)).isEmpty();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ChatRules.normaliseRoomDescription("x".repeat(201)));
    }

    @ParameterizedTest
    @CsvSource({
            "'Java & Azure!', java-azure",
            "'  Café Talk ', cafe-talk",
            "'Room 101', room-101",
            "'--Weird__Name--', weird-name"
    })
    void slugifyBuildsRoomIds(String name, String expected) {
        assertThat(ChatRules.slugify(name)).isEqualTo(expected);
    }

    @Test
    void slugifyTruncatesWithoutTrailingHyphen() {
        String slug = ChatRules.slugify("a".repeat(31) + " b and more");
        assertThat(slug).hasSizeLessThanOrEqualTo(ChatRules.MAX_ROOM_ID_LENGTH).doesNotEndWith("-");
        assertThat(ChatRules.isValidRoomId(slug)).isTrue();
    }

    @Test
    void slugifyRejectsNamesWithoutLettersOrDigits() {
        assertThatIllegalArgumentException().isThrownBy(() -> ChatRules.slugify("!!!"));
    }

    @Test
    void directChannelIsTheSameInBothDirections() {
        assertThat(ChatRules.directChannel("bob", "alice"))
                .isEqualTo(ChatRules.directChannel("alice", "bob"))
                .isEqualTo("dm:alice:bob");
        assertThat(ChatRules.roomChannel("general")).isEqualTo("room:general");
    }
}
