package io.github.omith786.chat.server.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.omith786.chat.protocol.ClientFrame;
import io.github.omith786.chat.protocol.ProtocolJson;
import io.github.omith786.chat.protocol.ServerFrame;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;

/** JSON encoding of WebSocket frames, using the protocol module's shared configuration. */
@Component
public class FrameCodec {

    private final ObjectMapper mapper = ProtocolJson.newMapper();

    /** Parses a client frame; throws {@link JsonProcessingException} for anything malformed. */
    public ClientFrame decode(String json) throws JsonProcessingException {
        ClientFrame frame = mapper.readValue(json, ClientFrame.class);
        if (frame == null) {
            throw new JsonMappingException(null, "Empty frame");
        }
        return frame;
    }

    /** Serialises a server frame into a WebSocket text message. */
    public TextMessage encode(ServerFrame frame) {
        try {
            return new TextMessage(mapper.writeValueAsString(frame));
        } catch (JsonProcessingException e) {
            // Frames are plain records of strings, numbers and instants, so this is a programming error.
            throw new IllegalStateException("Cannot serialise " + frame.getClass().getSimpleName(), e);
        }
    }
}
