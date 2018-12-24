package it.damore.solr.importexport;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import it.damore.solr.importexport.config.CommandLineConfig;
import it.damore.solr.importexport.config.ConfigFactory;
import it.damore.solr.importexport.config.SkipField;
import it.damore.solr.importexport.config.SkipField.MatchType;
import org.apache.commons.cli.ParseException;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrQuery.ORDER;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.CursorMarkParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;

/*
 * This file is part of solr-import-export-json. solr-import-export-json is free software: you can redistribute it
 * and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version. solr-import-export-json is distributed in the
 * hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details. You should have received a
 * copy of the GNU General Public License along with solr-import-export-json. If not, see
 * <http://www.gnu.org/licenses/>.
 */

/**
 * @author freedev Import and Export of a Solr collection
 */
public class App {

  private static Logger            logger       = LoggerFactory.getLogger(App.class);
  private static CommandLineConfig config       = null;
  private static ObjectMapper      objectMapper = new ObjectMapper();
  private static long              counter;
  private static long              skipCount;
  private static Integer           commitAfter;
  private static long              lastCommit = 0;
  private static int               numOfFailures = 0;

  private static Set<SkipField> skipFieldsEquals;
  private static Set<SkipField> skipFieldsStartWith;
  private static Set<SkipField> skipFieldsEndWith;

  /**
   * @param counter
   *          the counter to set
   */
  public static long incrementCounter(long counter) {
    App.counter += counter;
    return App.counter;
  }

  public static void main(String[] args) throws IOException, ParseException, URISyntaxException {

    config = ConfigFactory.getConfigFromArgs(args);

    skipFieldsEquals = config.getSkipFieldsSet()
                             .stream()
                             .filter(s -> s.getMatch() == MatchType.EQUAL)
                             .collect(Collectors.toSet());
    skipFieldsStartWith = config.getSkipFieldsSet()
                                .stream()
                                .filter(s -> s.getMatch() == MatchType.STARTS_WITH)
                                .collect(Collectors.toSet());
    skipFieldsEndWith = config.getSkipFieldsSet()
                              .stream()
                              .filter(s -> s.getMatch() == MatchType.ENDS_WITH)
                              .collect(Collectors.toSet());
    skipCount = config.getSkipCount();
    commitAfter = config.getCommitAfter();

    logger.info("Found config: " + config);

    if (config.getUniqueKey() == null) {
      readUniqueKeyFromSolrSchema();
    }

    try (HttpSolrClient client = new HttpSolrClient.Builder().withBaseSolrUrl(config.getSolrUrl()).build()) {

      try {
        switch (config.getActionType()) {
        case EXPORT:
        case BACKUP:

          readAllDocuments(client, new File(config.getFileName()));
          break;

        case RESTORE:
        case IMPORT:

          writeAllDocuments(client, new File(config.getFileName()));
          break;

        default:
          throw new RuntimeException("unsupported sitemap type");
        }

        logger.info("Build complete.");

      } catch (SolrServerException | IOException e) {
        // TODO Auto-generated catch block
        e.printStackTrace();
      } finally {
        System.out.println("Number of failures " + numOfFailures);
      }
    }

  }

  /**
   * @throws IOException
   * @throws JsonParseException
   * @throws JsonMappingException
   * @throws MalformedURLException
   */

  private static void readUniqueKeyFromSolrSchema() throws IOException, JsonParseException, JsonMappingException, MalformedURLException {
    String sUrl = config.getSolrUrl() + "/schema/uniquekey?wt=json";
    Map<String, Object> uniqueKey = objectMapper.readValue(readUrl(sUrl), new TypeReference<Map<String, Object>>() {
    });
    if (uniqueKey.containsKey("uniqueKey")) {
      config.setUniqueKey((String) uniqueKey.get("uniqueKey"));
    } else {
      logger.warn("unable to find valid uniqueKey defaulting to \"id\".");
    }
  }

  /**
   * @param sUrl
   * @return
   * @throws MalformedURLException
   * @throws IOException
   */
  private static String readUrl(String sUrl) throws MalformedURLException, IOException {
    StringBuilder sbJson = new StringBuilder();
    URL url = new URL(sUrl);
    String userInfo = url.getUserInfo();
    URLConnection openConnection = url.openConnection();
    if (userInfo != null && !userInfo.isEmpty()) {
      String authStr = Base64.getEncoder()
                             .encodeToString(userInfo.getBytes());
      openConnection.setRequestProperty("Authorization", "Basic " + authStr);
    }

    BufferedReader in = new BufferedReader(new InputStreamReader(openConnection.getInputStream()));
    String inputLine;
    while ((inputLine = in.readLine()) != null)
      sbJson.append(inputLine);
    in.close();
    return sbJson.toString();
  }

  /**
   * @param j
   * @return
   */
  private static SolrInputDocument json2SolrInputDocument(String j) {

    try {
      SolrInputDocument s = new SolrInputDocument();
      Map<String, Object> map = objectMapper.readValue(j, new TypeReference<Map<String, Object>>() {
      });
      for (Entry<String, Object> e : map.entrySet()) {
        if (!e.getKey()
              .equals("_version_"))
          s.addField(e.getKey(), e.getValue());
      }
      return s;
    } catch (IOException e) {
      logger.error("Error while converting json2SolrInputDocument. json = " + j + " Error = " + e.getMessage());
      numOfFailures++;
      e.printStackTrace();
      return null;
    }
  }

  /**
   * @param client
   * @param outputFile
   * @throws FileNotFoundException
   * @throws IOException
   * @throws SolrServerException
   */
  private static void writeAllDocuments(HttpSolrClient client, File outputFile) throws FileNotFoundException, IOException,
                                                                                SolrServerException {


    if (!skipFieldsEndWith.isEmpty()) {
      throw new RuntimeException("skipFieldsEndWith are not supported at writing time");
    }
    if (!config.getDryRun() && config.getDeleteAll()) {
      logger.info("delete all!");
      client.deleteByQuery("*:*");
    }
    logger.info("Reading " + config.getFileName());

    try (BufferedReader pw = new BufferedReader(new FileReader(outputFile))) {
      pw.lines().collect(StreamUtils.batchCollector(config.getBlockSize(), l -> {
        List<SolrInputDocument> collect = l.stream()
                .map(App::json2SolrInputDocument)
                .filter(Objects::nonNull)
                .map(d -> {
                  if (d.containsKey("_src_")) {
                    d.removeField("_src_");
                    d.addField("_src_", null);
                  }
                  if (d.containsKey("log") && d.getField("log") != null && d.getField("log").toString().length() > 0) {
                    String log = d.getField("log").toString();
                    d.removeField("log");
                    log = log.substring(0, Math.min(32765, log.length()));

                    if (!d.containsKey("environments.log")) {
                      d.addField("environments.log", log);
                    } else if (d.containsKey("environments.log") && d.getField("environments.log") != null && d.getField("environments.log").toString().trim().length() <= 0) {
                      d.removeField("environments.log");
                      d.addField("environments.log", log);
                    }
                  }

                  skipFieldsEquals.forEach(f -> d.removeField(f.getText()));
                  if (!skipFieldsStartWith.isEmpty()) {
                    d.getFieldNames().removeIf(name -> skipFieldsStartWith.stream()
                            .anyMatch(skipField -> name.startsWith(skipField.getText())));
                  }
                  if (!skipFieldsEndWith.isEmpty()) {
                    d.getFieldNames().removeIf(name -> skipFieldsEndWith.stream()
                            .anyMatch(skipField -> name.endsWith(skipField.getText())));
                  }
                  return d;
                })
                .collect(Collectors.toList());
        if (!insertBatch(client, collect)) {
          int retry = 5;
          while (--retry > 0 && !insertBatch(client, collect))//randomly when imported 10M documents, solr failed on Timeout exactly 10 minutes..
            ;
        }
      }));
    }

    commit(client);

  }

  private static boolean insertBatch(HttpSolrClient client, List<SolrInputDocument> collect) {
    try {

      if (!config.getDryRun()) {
        logger.info("adding " + collect.size() + " documents (" + incrementCounter(collect.size()) + ")");
        if(counter >= skipCount){
          client.add(collect);
          if(commitAfter != null && counter - lastCommit > commitAfter){
            commit(client);
            lastCommit = counter;
          }
        } else {
          logger.info("Skipping as current number of counter :" + counter +" is smaller than skipCount: " + skipCount);
        }
      }
    } catch (SolrServerException | IOException e) {
      logger.error("Problem while saving", e);
      return false;
    }
    return true;
  }

  private static void commit(HttpSolrClient client) throws SolrServerException, IOException {
    if (!config.getDryRun()) {
      client.commit();
      logger.info("Committed");
    }
  }

  /**
   * @param client
   * @param outputFile
   * @throws SolrServerException
   * @throws IOException
   */
  private static void readAllDocuments(HttpSolrClient client, File outputFile) throws SolrServerException, IOException {

    SolrQuery solrQuery = new SolrQuery();
    solrQuery.setTimeAllowed(-1);
    solrQuery.setQuery("*:*");
    solrQuery.setFields("*");
    if (config.getFilterQuery() != null) {
      solrQuery.addFilterQuery(config.getFilterQuery());
    }
    solrQuery.setRows(0);

    solrQuery.addSort(config.getUniqueKey(), ORDER.asc); // Pay attention to this line

    String cursorMark = CursorMarkParams.CURSOR_MARK_START;

    TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

    DateFormat df = new SimpleDateFormat(config.getDateTimeFormat());
    objectMapper.setDateFormat(df);
    objectMapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    QueryResponse r = client.query(solrQuery);

    long nDocuments = r.getResults()
                       .getNumFound();
    logger.info("Found " + nDocuments + " documents");

    if (!config.getDryRun()) {
      logger.info("Creating " + config.getFileName());

      try (PrintWriter pw = new PrintWriter(outputFile)) {
        solrQuery.setRows(config.getBlockSize());
        boolean done = false;
        while (!done) {
          solrQuery.set(CursorMarkParams.CURSOR_MARK_PARAM, cursorMark);
          QueryResponse rsp = client.query(solrQuery);
          String nextCursorMark = rsp.getNextCursorMark();
          if (nextCursorMark == null) {
            logger.warn("ATTENTION: you're dealing with a old version of Solr which does not support cursors");
          }

          SolrDocumentList results = rsp.getResults();
          for (SolrDocument d : results) {
            skipFieldsEquals.forEach(f -> d.removeFields(f.getText()));
            if (skipFieldsStartWith.size() > 0 || skipFieldsEndWith.size() > 0) {
              Map<String, Object> collect = d.entrySet()
                                             .stream()
                                             .filter(e -> !skipFieldsStartWith.stream()
                                                                              .filter(f -> e.getKey()
                                                                                            .startsWith(f.getText()))
                                                                              .findFirst()
                                                                              .isPresent())
                                             .filter(e -> !skipFieldsEndWith.stream()
                                                                            .filter(f -> e.getKey()
                                                                                          .endsWith(f.getText()))
                                                                            .findFirst()
                                                                            .isPresent())
                                             .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
              pw.write(objectMapper.writeValueAsString(collect));
            } else {
              pw.write(objectMapper.writeValueAsString(d));
            }
            pw.write("\n");
          }
          if (nextCursorMark == null || cursorMark.equals(nextCursorMark)) {
            done = true;
          } else {
            logger.info("reading " + results.size() + " documents (" + incrementCounter(results.size()) + ")");
          }

          cursorMark = nextCursorMark;
        }

      }
    }

  }

}
