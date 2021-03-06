/*************************************************************************
*                                                                        *
*  This file is part of the 20n/act project.                             *
*  20n/act enables DNA prediction for synthetic biology/bioengineering.  *
*  Copyright (C) 2017 20n Labs, Inc.                                     *
*                                                                        *
*  Please direct all queries to act@20n.com.                             *
*                                                                        *
*  This program is free software: you can redistribute it and/or modify  *
*  it under the terms of the GNU General Public License as published by  *
*  the Free Software Foundation, either version 3 of the License, or     *
*  (at your option) any later version.                                   *
*                                                                        *
*  This program is distributed in the hope that it will be useful,       *
*  but WITHOUT ANY WARRANTY; without even the implied warranty of        *
*  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the         *
*  GNU General Public License for more details.                          *
*                                                                        *
*  You should have received a copy of the GNU General Public License     *
*  along with this program.  If not, see <http://www.gnu.org/licenses/>. *
*                                                                        *
*************************************************************************/

package com.act.lcms.db.io;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class DB implements AutoCloseable {
  public enum OPERATION_PERFORMED {
    CREATE,
    READ,
    UPDATE,
    DELETE,
    ERROR,
  }

  public static final String DEFAULT_HOST = "localhost";
  public static final Integer DEFAULT_PORT = 5432;
  public static final String DEFAULT_DB_NAME = "lcms";

  public static final String DB_OPTION_URL = "db-url";
  public static final String DB_OPTION_HOST = "db-host";
  public static final String DB_OPTION_PORT = "db-port";
  public static final String DB_OPTION_USERNAME = "db-user";
  public static final String DB_OPTION_PASSWORD = "db-pass";
  public static final String DB_OPTION_DB_NAME = "db-name";


  public static final List<Option.Builder> DB_OPTION_BUILDERS = new ArrayList<Option.Builder>() {{
    // DB connection options.
    add(Option.builder()
            .argName("database url")
            .desc("The url to use when connecting to the LCMS db")
            .hasArg()
            .longOpt(DB_OPTION_URL)
    );
    add(Option.builder("H")
            .argName("database host")
            .desc(String.format("The LCMS DB host (default = %s)", DEFAULT_HOST))
            .hasArg()
            .longOpt(DB_OPTION_HOST)
    );
    add(Option.builder("P")
            .argName("database port")
            .desc(String.format("The LCMS DB port (default = %d)", DEFAULT_PORT))
            .hasArg()
            .longOpt(DB_OPTION_PORT)
    );
    add(Option.builder()
            .argName("database user")
            .desc("The LCMS DB user")
            .hasArg()
            .longOpt(DB_OPTION_USERNAME)
    );
    add(Option.builder()
            .argName("database password")
            .desc("The LCMS DB password")
            .hasArg()
            .longOpt(DB_OPTION_PASSWORD)
    );
    add(Option.builder("db")
            .argName("database name")
            .desc(String.format("The LCMS DB name (default = %s)", DEFAULT_DB_NAME))
            .hasArg()
            .longOpt(DB_OPTION_DB_NAME)
    );
  }};

  Connection conn;

  public DB connectToDB(String connStr) throws ClassNotFoundException, SQLException {
    /* Explicitly load the PostgreSQL driver class before trying to connect.
     * See https://jdbc.postgresql.org/documentation/94/load.html. */
    Class.forName("org.postgresql.Driver");
    this.conn = DriverManager.getConnection(connStr);
    return this;
  }

  public DB connectToDB(String host, Integer port, String databaseName, String user, String password)
      throws ClassNotFoundException, SQLException {
    Class.forName("org.postgresql.Driver");
    String url = String.format("jdbc:postgresql://%s:%d/%s",
        host == null ? DEFAULT_HOST : host,
        port == null ? DEFAULT_PORT : port,
        databaseName == null ? DEFAULT_DB_NAME : databaseName);
    this.conn = DriverManager.getConnection(url, user, password);
    return this;
  }

  public Connection getConn() {
    return conn;
  }

  @Override
  public void close() throws SQLException {
    if (conn != null && !conn.isClosed()) {
      conn.close();
    }
  }

  public static DB openDBFromCLI(CommandLine cl) throws ClassNotFoundException, SQLException {
    if (cl.hasOption(DB_OPTION_URL)) {
      return new DB().connectToDB(cl.getOptionValue(DB_OPTION_URL));
    } else {
      Integer port = null;
      if (cl.getOptionValue(DB_OPTION_PORT) != null) {
        port = Integer.parseInt(cl.getOptionValue(DB_OPTION_PORT));
      }
      return new DB().connectToDB(cl.getOptionValue(DB_OPTION_HOST), port, cl.getOptionValue(DB_OPTION_DB_NAME),
          cl.getOptionValue(DB_OPTION_USERNAME), cl.getOptionValue(DB_OPTION_PASSWORD));
    }
  }
}
