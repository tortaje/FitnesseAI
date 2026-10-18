package com.fitnesseai.plugin;

import fitnesse.FitNesseContext;
import fitnesse.http.Request;
import fitnesse.http.Response;
import fitnesse.http.SimpleResponse;
import fitnesse.responders.editing.EditResponder;

import java.nio.charset.StandardCharsets;

public class AiVerifyEditResponder extends EditResponder {
  static final String CSS_PATH = "files/fitnesse/ai-verify/ai-verify.css";
  static final String JS_PATH = "files/fitnesse/ai-verify/ai-verify.js";

  @Override
  public Response makeResponse(FitNesseContext context, Request request) throws Exception {
    Response response = super.makeResponse(context, request);
    if (response instanceof SimpleResponse simpleResponse && simpleResponse.getStatus() == 200) {
      String html = new String(simpleResponse.getContentBytes(), StandardCharsets.UTF_8);
      String contextRoot = context.contextRoot == null ? "/" : context.contextRoot;
      String injection = "<link rel=\"stylesheet\" href=\"" + contextRoot + CSS_PATH + "\" />\n"
        + "<script src=\"" + contextRoot + JS_PATH + "\"></script>\n";
      int bodyClose = html.lastIndexOf("</body>");
      if (bodyClose != -1) {
        html = html.substring(0, bodyClose) + injection + html.substring(bodyClose);
        simpleResponse.setContent(html);
      }
    }
    return response;
  }
}
