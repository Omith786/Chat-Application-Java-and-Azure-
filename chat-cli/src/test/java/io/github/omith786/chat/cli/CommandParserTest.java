package io.github.omith786.chat.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CommandParserTest {

    @Test
    void plainTextIsSaid() {
        assertThat(CommandParser.parse("  hello there  ")).isEqualTo(new Command.Say("hello there"));
    }

    @Test
    void doubleSlashSendsALiteralSlash() {
        assertThat(CommandParser.parse("//shrug")).isEqualTo(new Command.Say("/shrug"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t"})
    void blankLinesAreEmpty(String line) {
        assertThat(CommandParser.parse(line)).isEqualTo(new Command.Empty());
    }

    @Test
    void nullIsEmpty() {
        assertThat(CommandParser.parse(null)).isEqualTo(new Command.Empty());
    }

    @Test
    void parsesRoomCommands() {
        assertThat(CommandParser.parse("/join general")).isEqualTo(new Command.Join("general"));
        assertThat(CommandParser.parse("/J general extra words")).isEqualTo(new Command.Join("general"));
        assertThat(CommandParser.parse("/leave")).isEqualTo(new Command.Leave(null));
        assertThat(CommandParser.parse("/leave random")).isEqualTo(new Command.Leave("random"));
        assertThat(CommandParser.parse("/switch random")).isEqualTo(new Command.Switch("random"));
        assertThat(CommandParser.parse("/switch @bob")).isEqualTo(new Command.Switch("@bob"));
        assertThat(CommandParser.parse("/rooms")).isEqualTo(new Command.Rooms());
        assertThat(CommandParser.parse("/create Java and Azure")).isEqualTo(new Command.Create("Java and Azure"));
        assertThat(CommandParser.parse("/who")).isEqualTo(new Command.Who(null));
        assertThat(CommandParser.parse("/who general")).isEqualTo(new Command.Who("general"));
    }

    @Test
    void parsesDirectMessagesKeepingTheWholeText() {
        assertThat(CommandParser.parse("/dm bob see you at   noon"))
                .isEqualTo(new Command.Direct("bob", "see you at   noon"));
        assertThat(CommandParser.parse("/msg @bob hi")).isEqualTo(new Command.Direct("bob", "hi"));
    }

    @Test
    void parsesHistoryWithBounds() {
        assertThat(CommandParser.parse("/history")).isEqualTo(new Command.History(CommandParser.DEFAULT_HISTORY));
        assertThat(CommandParser.parse("/history 5")).isEqualTo(new Command.History(5));
        assertThat(CommandParser.parse("/history 0")).isInstanceOf(Command.Invalid.class);
        assertThat(CommandParser.parse("/history 1000")).isInstanceOf(Command.Invalid.class);
        assertThat(CommandParser.parse("/history lots")).isInstanceOf(Command.Invalid.class);
    }

    @Test
    void parsesQuitAndHelpAliases() {
        assertThat(CommandParser.parse("/quit")).isEqualTo(new Command.Quit());
        assertThat(CommandParser.parse("/exit")).isEqualTo(new Command.Quit());
        assertThat(CommandParser.parse("/help")).isEqualTo(new Command.Help());
        assertThat(CommandParser.help()).contains("/join", "/dm", "/quit");
    }

    @Test
    void reportsUsageErrors() {
        assertThat(CommandParser.parse("/join")).isEqualTo(new Command.Invalid("Usage: /join <room>"));
        assertThat(CommandParser.parse("/dm bob")).isEqualTo(new Command.Invalid("Usage: /dm <user> <message>"));
        assertThat(CommandParser.parse("/create")).isInstanceOf(Command.Invalid.class);
        assertThat(CommandParser.parse("/switch")).isInstanceOf(Command.Invalid.class);
        assertThat(CommandParser.parse("/dance")).isEqualTo(new Command.Invalid("Unknown command /dance (try /help)"));
    }
}
