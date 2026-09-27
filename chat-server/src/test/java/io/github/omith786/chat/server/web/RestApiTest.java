package io.github.omith786.chat.server.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.omith786.chat.server.config.ServerInstance;
import io.github.omith786.chat.server.presence.PresenceRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class RestApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    PresenceRegistry presence;

    @Autowired
    ServerInstance instance;

    @Test
    void createsASessionWithACanonicalUsername() throws Exception {
        mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\" Rest_User \"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("rest_user"))
                .andExpect(jsonPath("$.token").value(startsWith("v1.")))
                .andExpect(jsonPath("$.expiresAt").exists());
    }

    @Test
    void rejectsInvalidUsernamesWithAProblemDetail() throws Exception {
        mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid"))
                .andExpect(jsonPath("$.detail").exists());
        mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON).content("not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refusesAUsernameThatIsAlreadyOnline() throws Exception {
        presence.userConnected(instance.id(), "taken-name");
        try {
            mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"Taken-Name\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("username_taken"));
        } finally {
            presence.userDisconnected(instance.id(), "taken-name");
        }
    }

    @Test
    void apiRequiresAValidBearerToken() throws Exception {
        mvc.perform(get("/api/rooms"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthorised"));
        mvc.perform(get("/api/rooms").header("Authorization", "Bearer v1.forged.1.sig"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/rooms").header("Authorization", "Basic abc"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void publicEndpointsNeedNoToken() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.broker.details.type").value("local"));
        mvc.perform(get("/actuator/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chat.broker").value("local"));
        mvc.perform(get("/index.html")).andExpect(status().isOk());
    }

    @Test
    void meReturnsTheTokenOwner() throws Exception {
        String token = token("me-user");
        mvc.perform(authed(get("/api/sessions/me"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("me-user"))
                .andExpect(jsonPath("$.online").value(false));
    }

    @Test
    void listsCreatesAndFetchesRooms() throws Exception {
        String token = token("room-maker");

        mvc.perform(authed(get("/api/rooms"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("general"))
                .andExpect(jsonPath("$[0].online").isNumber());

        mvc.perform(authed(post("/api/rooms"), token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"REST Created\",\"description\":\"via MockMvc\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("rest-created"))
                .andExpect(jsonPath("$.createdBy").value("room-maker"));

        mvc.perform(authed(post("/api/rooms"), token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"REST Created\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("room_exists"));

        mvc.perform(authed(get("/api/rooms/rest-created"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("via MockMvc"));
        mvc.perform(authed(get("/api/rooms/missing-room"), token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("no_such_room"));
        mvc.perform(authed(get("/api/rooms"), token))
                .andExpect(jsonPath("$[*].id", hasItem("rest-created")));
    }

    @Test
    void historyEndpointsValidateTheirParameters() throws Exception {
        String token = token("history-user");
        mvc.perform(authed(get("/api/rooms/general/messages?limit=5"), token)).andExpect(status().isOk());
        mvc.perform(authed(get("/api/rooms/general/messages?limit=0"), token)).andExpect(status().isBadRequest());
        mvc.perform(authed(get("/api/rooms/general/messages?before=abc"), token)).andExpect(status().isBadRequest());
        mvc.perform(authed(get("/api/rooms/Not_Valid/messages"), token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid"));
        mvc.perform(authed(get("/api/direct"), token)).andExpect(status().isOk());
        mvc.perform(authed(get("/api/direct/someone/messages"), token)).andExpect(status().isOk());
        mvc.perform(authed(get("/api/direct/NOT OK/messages"), token)).andExpect(status().isBadRequest());
    }

    @Test
    void presenceEndpointsReflectTheRegistry() throws Exception {
        String token = token("presence-user");
        presence.roomJoined(instance.id(), "general", "presence-user");
        try {
            mvc.perform(authed(get("/api/users/online"), token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasItem("presence-user")));
            mvc.perform(authed(get("/api/rooms/general/members"), token))
                    .andExpect(jsonPath("$", hasItem("presence-user")));
            mvc.perform(authed(get("/api/rooms/Bad!/members"), token)).andExpect(status().isBadRequest());
        } finally {
            presence.userDisconnected(instance.id(), "presence-user");
        }
    }

    private String token(String username) throws Exception {
        String body = mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asText();
    }

    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }
}
