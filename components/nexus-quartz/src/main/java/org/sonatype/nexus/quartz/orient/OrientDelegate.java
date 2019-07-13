/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.quartz.orient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.quartz.Job;
import org.quartz.JobDetail;
import org.quartz.TriggerKey;
import org.quartz.impl.JobDetailImpl;
import org.quartz.impl.jdbcjobstore.StdJDBCDelegate;
import org.quartz.spi.ClassLoadHelper;

/**
 * OrientDB specific JDBC job store delegate.
 *
 * Currently overrides {@link #selectJobForTrigger(Connection, ClassLoadHelper, TriggerKey, boolean)} since
 * the standard implementation uses a table join which is not supported by the OrientDB SQL dialect.
 *
 * @since 3.next
 */
public class OrientDelegate
    extends StdJDBCDelegate
{
  @Override
  public JobDetail selectJobForTrigger(final Connection conn,
                                       final ClassLoadHelper loadHelper,
                                       final TriggerKey triggerKey,
                                       boolean loadJobClass)
      throws ClassNotFoundException, SQLException
  {
    PreparedStatement ps = null;
    ResultSet rs = null;
    String triggerName;
    String triggerGroup;

    try {
      ps = conn.prepareStatement(rtp(SELECT_TRIGGER));
      ps.setString(1, triggerKey.getName());
      ps.setString(2, triggerKey.getGroup());
      rs = ps.executeQuery();

      if (rs.next()) {
        triggerName = rs.getString(1);
        triggerGroup = rs.getString(2);
      }
      else {
        if (logger.isDebugEnabled()) {
          logger.debug("No job for trigger '{}'.", triggerKey);
        }
        return null;
      }
    }
    finally {
      closeResultSet(rs);
      closeStatement(ps);
    }

    try {
      ps = conn.prepareStatement(rtp(SELECT_JOB_DETAIL));
      ps.setString(1, triggerName);
      ps.setString(2, triggerGroup);
      rs = ps.executeQuery();

      if (rs.next()) {
        JobDetailImpl job = new JobDetailImpl();
        job.setName(rs.getString(1));
        job.setGroup(rs.getString(2));
        job.setDurability(getBoolean(rs, 3));
        if (loadJobClass) {
          job.setJobClass(loadHelper.loadClass(rs.getString(4), Job.class));
        }
        job.setRequestsRecovery(getBoolean(rs, 5));

        return job;
      }
      else {
        return null;
      }
    } finally {
      closeResultSet(rs);
      closeStatement(ps);
    }
  }

  @Override
  protected Object getObjectFromBlob(final ResultSet rs, final String colName)
      throws ClassNotFoundException, IOException, SQLException
  {
    byte[] bytes = rs.getBytes(colName);

    if(bytes != null && bytes.length != 0) {
      InputStream binaryInput = new ByteArrayInputStream(bytes);
      try (ObjectInputStream in = new ObjectInputStream(binaryInput)) {
        return in.readObject();
      }
    }

    return null;
  }

  @Override
  protected Object getJobDataFromBlob(final ResultSet rs, final String colName)
      throws ClassNotFoundException, IOException, SQLException
  {
    if (canUseProperties()) {
      byte[] bytes = rs.getBytes(colName);
      if(bytes == null || bytes.length == 0) {
        return null;
      }
      return new ByteArrayInputStream(bytes);
    }
    return getObjectFromBlob(rs, colName);
  }
}