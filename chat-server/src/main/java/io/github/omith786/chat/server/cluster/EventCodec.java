package io.github.omith786.chat.server.cluster;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.omith786.chat.protocol.ProtocolJson;

/** JSON encoding of {@link ChatEvent}s for brokers that cross process boundaries. */
public final class EventCodec {

    private final ObjectMapper mapper = ProtocolJson.newMapper();

    /** Serialises an event. */
    public String encode(ChatEvent event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise " + event.getClass().getSimpleName(), e);
        }
    }

    /** Parses an event; throws {@link JsonProcessingException} if it is malformed or unknown. */
    public ChatEvent decode(String json) throws JsonProcessingException {
        return mapper.readValue(json, ChatEvent.class);
    }
}
