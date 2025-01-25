/**
 * Copyright (c) 2010-2024 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.linkplay.internal.transport.http;

import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * Represents the result of a LinkPlay command execution, handling both JSON and text responses
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class CommandResult {
    private static final Logger logger = LoggerFactory.getLogger(CommandResult.class);

    private final boolean success;
    private final @Nullable String content;
    private final @Nullable Exception error;
    private final @Nullable JsonObject jsonContent; // Cached parsed JSON if valid

    private CommandResult(boolean success, @Nullable String content, @Nullable Exception error) {
        this.success = success;
        this.content = content;
        this.error = error;
        this.jsonContent = parseJsonIfValid(content);
    }

    private static @Nullable JsonObject parseJsonIfValid(@Nullable String content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        try {
            return JsonParser.parseString(content).getAsJsonObject();
        } catch (JsonSyntaxException | IllegalStateException e) {
            logger.trace("Content is not valid JSON: {}", e.getMessage());
            return null;
        }
    }

    public static CommandResult text(String content) {
        return new CommandResult(true, content, null);
    }

    public static CommandResult error(String message) {
        return new CommandResult(false, null, new LinkPlayApiException(message));
    }

    public static CommandResult error(Exception e) {
        return new CommandResult(false, null, e);
    }

    public boolean isSuccess() {
        return success;
    }

    public boolean isJsonContent() {
        return jsonContent != null;
    }

    public Optional<JsonObject> getAsJson() {
        return Optional.ofNullable(jsonContent);
    }

    public Optional<String> getContent() {
        return Optional.ofNullable(content);
    }

    public String getErrorMessage() {
        if (error != null) {
            String message = error.getMessage();
            return message != null ? message : error.getClass().getSimpleName();
        }
        return "Unknown error";
    }

    /**
     * Get JSON content or throw exception if not available
     */
    public JsonObject getJsonOrThrow() throws LinkPlayApiException {
        if (!isSuccess()) {
            throw new LinkPlayApiException(getErrorMessage());
        }
        JsonObject result = jsonContent;
        if (result == null) {
            throw new LinkPlayApiException("No JSON content available");
        }
        return result;
    }

    /**
     * Get raw content or throw exception if not available
     */
    public String getContentOrThrow() throws LinkPlayApiException {
        if (!isSuccess()) {
            throw new LinkPlayApiException(getErrorMessage());
        }
        String result = content;
        if (result == null) {
            throw new LinkPlayApiException("No content available");
        }
        return result;
    }

    /**
     * Get the JSON response if any
     */
    public @Nullable JsonObject getResponse() {
        return jsonContent;
    }
}
