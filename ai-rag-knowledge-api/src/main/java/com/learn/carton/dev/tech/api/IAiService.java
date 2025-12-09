package com.learn.carton.dev.tech.api;


import org.springframework.ai.chat.ChatResponse;
import reactor.core.publisher.Flux;

/**
 * @author Carton
 * @date 2025/12/3 16:06
 * @description TODO:
 */

public interface IAiService {

    ChatResponse generate(String model, String message);

    Flux<ChatResponse> generateStream(String model, String message);

    Flux<ChatResponse> generateStreamRag(String model, String ragTag, String message);
}
