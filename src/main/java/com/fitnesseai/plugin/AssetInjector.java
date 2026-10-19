package com.fitnesseai.plugin;

import fitnesse.http.Response;
import fitnesse.http.SimpleResponse;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

class AssetInjector {
  static final String CSS_PATH = "files/fitnesse/ai-verify/ai-verify.css";
  static final String JS_PATH = "files/fitnesse/ai-verify/ai-verify.js";

  private AssetInjector() {
  }

  static void inject(Response response, String contextRoot) throws UnsupportedEncodingException {
    if (response instanceof SimpleResponse simpleResponse && simpleResponse.getStatus() == 200) {
      String html = new String(simpleResponse.getContentBytes(), StandardCharsets.UTF_8);
      String root = contextRoot == null ? "/" : contextRoot;
      String injection = "<link rel=\"stylesheet\" href=\"" + root + CSS_PATH + "\" />\n"
        + "<script src=\"" + root + JS_PATH + "\"></script>\n";
      int bodyClose = html.lastIndexOf("</body>");
      if (bodyClose != -1) {
        html = html.substring(0, bodyClose) + injection + html.substring(bodyClose);
        simpleResponse.setContent(html);
      }
    }
  }
}
