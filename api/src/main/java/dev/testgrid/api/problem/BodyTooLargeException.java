package dev.testgrid.api.problem;

import java.io.IOException;

/** Thrown while reading a request body that grows past the limit (no Content-Length given). */
class BodyTooLargeException extends IOException {

  BodyTooLargeException(long limit) {
    super("request body exceeds " + limit / 1024 + " KB");
  }
}
