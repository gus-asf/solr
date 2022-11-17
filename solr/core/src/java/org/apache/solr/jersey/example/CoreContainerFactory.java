package org.apache.solr.jersey.example;

import org.apache.solr.core.CoreContainer;
import org.apache.solr.servlet.CoreContainerProvider;
import org.glassfish.hk2.api.Factory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletContext;
import javax.servlet.UnavailableException;
import javax.ws.rs.core.Context;
import java.lang.invoke.MethodHandles;

public class CoreContainerFactory implements Factory<CoreContainer> {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  @Context
  ServletContext ctx;


  @Override
  public CoreContainer provide() {
    try {
      log.info("provide");
      return CoreContainerProvider.serviceForContext(ctx).getService().getCoreContainer();
    } catch (InterruptedException | UnavailableException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void dispose(CoreContainer instance) {

  }
}
