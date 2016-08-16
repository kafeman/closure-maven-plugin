package com.google.closure.plugin.extract;

import com.google.closure.plugin.common.Options;

/**
 * Specify how to extract JS, CSS, Proto, and Soy dependencies from Maven
 * artifacts.
 */
public final class Extracts extends Options {

  private static final long serialVersionUID = 6857217093408402466L;

  /** Artifacts from which to extract dependencies or source files. */
  public Extract[] extract;

  @Override
  protected void createLazyDefaults() {
    if (extract == null) {
      extract = new Extract[0];
    }
  }
}
