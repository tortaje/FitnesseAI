package com.fitnesseai.plugin;

import fitnesse.FitNesseContext;
import fitnesse.http.Request;
import fitnesse.http.Response;
import fitnesse.responders.editing.NewPageResponder;

public class AiVerifyNewPageResponder extends NewPageResponder {
  @Override
  public Response makeResponse(FitNesseContext context, Request request) throws Exception {
    Response response = super.makeResponse(context, request);
    AssetInjector.inject(response, context.contextRoot);
    return response;
  }
}
