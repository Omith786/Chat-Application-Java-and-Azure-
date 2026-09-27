package io.github.omith786.chat.server.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "chat.rate-limits.sessions.capacity=2",
        "chat.rate-limits.sessions.refill-period=1h"
})
@AutoConfigureMockMvc
class RateLimitApiTest {

    @Autowired
    MockMvc mvc;

    @Test
    void sessionCreationIsRateLimitedPerClientAddress() throws Exception {
        for (String name : new String[]{"limited-a", "limited-b"}) {
            mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + name + "\"}"))
                    .andExpect(status().isCreated());
        }
        mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"limited-c\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("rate_limited"));

        // A different client address has its own bucket.
        mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"limited-d\"}")
                        .with(request -> {
                            request.setRemoteAddr("10.0.0.99");
                            return request;
                        }))
                .andExpect(status().isCreated());
    }
}
