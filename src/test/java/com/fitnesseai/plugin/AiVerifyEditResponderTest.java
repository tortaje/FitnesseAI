package com.fitnesseai.plugin;

import fitnesse.FitNesseContext;
import fitnesse.Responder;
import fitnesse.http.MockRequest;
import fitnesse.http.Response;
import fitnesse.http.SimpleResponse;
import fitnesse.testutil.FitNesseUtil;
import fitnesse.wiki.PathParser;
import fitnesse.wiki.WikiPage;
import fitnesse.wiki.WikiPageUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiVerifyEditResponderTest {

  private FitNesseContext context;

  @AfterEach
  void tearDown() throws IOException {
    if (context != null) {
      FitNesseUtil.destroyTestContext(context);
      context = null;
    }
  }

  private FitNesseContext makeContext() {
    return FitNesseUtil.makeTestContext(new Properties());
  }

  private static MockRequest editRequest() {
    MockRequest request = new MockRequest("TestPage");
    request.setRequestLine("GET /TestPage?edit HTTP/1.1");
    request.addInput("responder", "edit");
    return request;
  }

  @Test
  void pluginFactoryOverridesEditResponderAndAddsAiVerify() {
    context = makeContext();

    assertEquals(AiVerifyEditResponder.class, context.responderFactory.getResponderClass("edit"));
    assertEquals(AiVerifyResponder.class, context.responderFactory.getResponderClass("ai-verify"));
  }

  @Test
  void editPageHtmlContainsAssetTags() throws Exception {
    context = makeContext();
    WikiPage root = context.getRootPage();
    WikiPageUtil.addPage(root, PathParser.parse("TestPage"), "!CONTENTS");

    MockRequest request = editRequest();
    Responder responder = context.responderFactory.makeResponder(request);
    assertTrue(responder instanceof AiVerifyEditResponder);
    Response response = responder.makeResponse(context, request);

    assertTrue(response instanceof SimpleResponse);
    String html = new String(((SimpleResponse) response).getContentBytes(), StandardCharsets.UTF_8);
    assertTrue(html.contains("files/fitnesse/ai-verify/ai-verify.css"), "CSS tag injected");
    assertTrue(html.contains("files/fitnesse/ai-verify/ai-verify.js"), "JS tag injected");
    int linkIndex = html.indexOf("ai-verify.css");
    int bodyClose = html.lastIndexOf("</body>");
    assertTrue(linkIndex < bodyClose, "tags must be injected before </body>");
  }

  @Test
  void assetsAreServedFromClasspath() throws Exception {
    context = makeContext();

    MockRequest request = new MockRequest("files/fitnesse/ai-verify/ai-verify.js");
    request.setRequestLine("GET /files/fitnesse/ai-verify/ai-verify.js HTTP/1.1");
    Responder responder = context.responderFactory.makeResponder(request);
    Response response = responder.makeResponse(context, request);

    assertTrue(response instanceof SimpleResponse);
    SimpleResponse simple = (SimpleResponse) response;
    assertEquals(200, simple.getStatus());
    String body = new String(simple.getContentBytes(), StandardCharsets.UTF_8);
    assertNotNull(body);
    assertTrue(body.contains("Verify with AI"), "placeholder JS should mention the button label");
  }
}
