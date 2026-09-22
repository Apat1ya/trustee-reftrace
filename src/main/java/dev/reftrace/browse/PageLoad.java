package dev.reftrace.browse;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatusCode;

import java.net.URI;

public record PageLoad(URI requestedUrl, URI finalUrl, @Nullable HttpStatusCode httpStatus) {
}
