package act.installer.brenda;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;

public class BrendaChebiOntology {

  private static final Logger LOGGER = LogManager.getLogger(BrendaChebiOntology.class);

  public static class ChebiOntology {

    // The following query allows to retrieve the terms (basic string defining an ontology) and definitions
    // (when it exists) corresponding to a ChEBI id (ex: "CHEBI:46195") to create ChebiOntology objects.
    public static final String QUERY = StringUtils.join(new String[]{
        "SELECT",
        "  terms.id_go,",
        "  terms.term,",
        "  definitions.definition",
        "FROM ontology_chebi_Terms terms",
        "LEFT JOIN ontology_chebi_Definitions definitions",
        "ON terms.id_go = definitions.id_go"
    }, " ");

    private String chebiId;
    private String term;
    private String definition;

    public ChebiOntology(String chebiId, String term, String definition) {
      this.chebiId = chebiId;
      this.term = term;
      this.definition = definition;
    }

    public String getChebiId() {
      return this.chebiId;
    }

    public String getTerm() {
      return this.term;
    }

    public String getDefinition() {
      return this.definition;
    }

    // We override the equals and hashCode methods to make a ChebiOntology object hashable and allow construction of
    // HashSet and HashMap of ChebiOntology objects.
    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      ChebiOntology that = (ChebiOntology) o;
      return (chebiId != null) ? chebiId.equals(that.chebiId) : (that.chebiId == null);
    }

    @Override
    public int hashCode() {
      int result = chebiId.hashCode();
      return result;
    }

    /* This function creates a ChebiOntology object from a ResultSet resulting from a SQL query.
     * It pulls the 3 first fields from the query, assuming the order:
     * ChebiId,
     * Term,
     * Definition
     */
    public static ChebiOntology fromResultSet(ResultSet resultSet) throws SQLException {
      return new ChebiOntology(
          resultSet.getString(1),
          resultSet.getString(2),
          resultSet.getString(3));
    }
  }

  public static class ChebiRelationship {

    // The following query allows to retrieve the relations of a given type, passed as argument.
    // It is restricted to ids starting with the string 'CHEBI:'
    public static final String QUERY = StringUtils.join(new String[]{
        "SELECT",
        " id_go,",
        " rel_id_go",
        "FROM ontology_chebi_Relations",
        "WHERE type = ?",
        "AND id_go like 'CHEBI:%'",
        "AND rel_id_go like 'CHEBI:%'"
    }, " ");

    private String chebiId;
    private String parentChebiId;

    public ChebiRelationship(String chebiId, String parentChebiId) {
      this.chebiId = chebiId;
      this.parentChebiId = parentChebiId;
    }

    public String getChebiId() {
      return chebiId;
    }

    public String getParentChebiId() {
      return parentChebiId;
    }


    /* This function creates a ChebiOntology object from a ResultSet resulting from a SQL query.
     * It pulls the 3 first fields from the query, assuming the order:
     * chebiId,
     * parentChebiId
     * if type = 1, chebiId refers to a subtype of the ontology parentChebiId
     * if type = 12, parentChebiId refers to a role of the ontology chebiId
     */
    public static ChebiRelationship fromResultSet(ResultSet resultSet) throws SQLException {
      return new ChebiRelationship(
          resultSet.getString(1),
          resultSet.getString(2));
    }
  }


  public static class ChebiApplicationSet {

    private HashSet<ChebiOntology> directApplications;
    private HashSet<ChebiOntology> mainApplications;

    public ChebiApplicationSet(HashSet<ChebiOntology> directApplications,
                               HashSet<ChebiOntology> mainApplications) {
      this.directApplications = directApplications;
      this.mainApplications = mainApplications;
    }

    public HashSet<ChebiOntology> getMainApplications() {
      return mainApplications;
    }

    public HashSet<ChebiOntology> getDirectApplications() {
      return directApplications;
    }
  }


  /**
   * This function fetches an ontology map (ChebiId -> ChebiOntology) given a connexion to the BRENDA DB.
   * @param brendaDB A SQLConnexion object to the BRENDA DB
  * @return a map from ChebiId to ChebiOntology objects
  * @throws SQLException
  */
  public static HashMap<String, ChebiOntology> fetchOntologyMap(SQLConnection brendaDB) throws SQLException {
    int ontologiesProcessed = 0;

    HashMap<String, ChebiOntology> ontologyMap = new HashMap<>();

    Iterator<ChebiOntology> ontologies = brendaDB.getChebiOntologies();

    while (ontologies.hasNext()) {
      ChebiOntology ontology = ontologies.next();
      ontologyMap.put(ontology.getChebiId(), ontology);
      ontologiesProcessed++;
    }
    LOGGER.debug("Done processing ontologies");
    LOGGER.debug("Found %s ontologies", ontologiesProcessed);

    return ontologyMap;
  }

  /**
   * This function fetches relationships of type 'isSubTypeOf' between ChebiOntology objects given a connexion to the
   * BRENDA DB. The output is a map from a ChebiOntology object to all its subtypes ontologies.
   * @param brendaDB a SQLConnexion object to the BRENDA DB
   * @param ontologyMap a map from ChebiId to ChebiOntology objects
   * @return a map from ChebiOntology objects to a set of ChebiOntology objects (its subtypes).
   * @throws SQLException
   */
  public static HashMap<ChebiOntology, HashSet<ChebiOntology>> fetchIsSubtypeOfRelationships(
      SQLConnection brendaDB,
      HashMap<String, ChebiOntology> ontologyMap)
      throws SQLException {

    final int IS_SUBTYPE_OF_RELATIONSHIP_TYPE = 1;
    int relationshipsProcessed = 0;

    HashMap<ChebiOntology, HashSet<ChebiOntology>> isSubtypeOfRelationships = new HashMap<>();

    Iterator<ChebiRelationship> relationships = brendaDB.getChebiRelationships(IS_SUBTYPE_OF_RELATIONSHIP_TYPE);

    while (relationships.hasNext()) {
      ChebiRelationship relationship = relationships.next();
      // Note that we use the 'parent' as the key here and insert the 'child' in the value set.
      // Hence we get a map of ontologies to their subtype.
      HashSet<ChebiOntology> ontologySet = isSubtypeOfRelationships.get(ontologyMap.get(relationship.getParentChebiId()));
      if (ontologySet == null) {
        ontologySet = new HashSet<>();
        isSubtypeOfRelationships.put(ontologyMap.get(relationship.getParentChebiId()), ontologySet);
      }
      ontologySet.add(ontologyMap.get(relationship.getChebiId()));
      relationshipsProcessed++;
    }

    LOGGER.debug("Done processing 'is subtype of' relationships");
    LOGGER.debug("Found %s 'is subtype of' relationships", relationshipsProcessed);

    return isSubtypeOfRelationships;
  }

  /**
   * This function fetches relationships of type 'hasRole' between ChebiOntology objects given a connexion to the
   * BRENDA DB. The output is a map from a ChebiOntology object to all its roles that are 'applications'.
   * @param brendaDB a SQLConnexion object to the BRENDA DB
   * @param ontologyMap a map from ChebiId to ChebiOntology objects
   * @param allApplications the set of all ontologies that have the ontology 'application' as a parent
   * @return a map from ChebiOntology objects to a set of ChebiOntology objects (its roles).
   * @throws SQLException
   */
  public static HashMap<ChebiOntology, HashSet<ChebiOntology>> fetchHasRoleRelationships(
      SQLConnection brendaDB,
      HashMap<String, ChebiOntology> ontologyMap,
      ArrayList<ChebiOntology> allApplications)
      throws SQLException {

    final int HAS_ROLE_RELATIONSHIP_TYPE = 12;
    int relationshipsProcessed = 0;

    HashMap<ChebiOntology, HashSet<ChebiOntology>> hasRoleRelationships = new HashMap<>();

    Iterator<ChebiRelationship> relationships = brendaDB.getChebiRelationships(HAS_ROLE_RELATIONSHIP_TYPE);

    while (relationships.hasNext()) {
      ChebiRelationship relationship = relationships.next();
      HashSet<ChebiOntology> ontologySet = hasRoleRelationships.get(ontologyMap.get(relationship.getChebiId()));
      if (ontologySet == null) {
        ontologySet = new HashSet<>();
        hasRoleRelationships.put(ontologyMap.get(relationship.getChebiId()), ontologySet);
      }
      ChebiOntology ontology = ontologyMap.get(relationship.getParentChebiId());
      if (allApplications.contains(ontology)) {
        ontologySet.add(ontology);
      }
      relationshipsProcessed++;
    }

    LOGGER.debug("Done processing 'has role' relationships");
    LOGGER.debug("Found %s 'has role' relationships", relationshipsProcessed);

    return hasRoleRelationships;
  }

  /**
   * This function fetches and construct the set of main and direct applications for each ontology that has a role.
   * @param brendaDB a SQLConnexion object to the BRENDA DB
   * @param ontologyMap a map from ChebiId to ChebiOntology objects
   * @return a map from ChebiOntology objects to a ChebiApplicationSet object
   * @throws SQLException
   */
  public static HashMap<ChebiOntology, ChebiApplicationSet> getApplications(
      SQLConnection brendaDB,
      HashMap<String, ChebiOntology> ontologyMap) throws SQLException {

    final String APPLICATION_CHEBI_ID = "CHEBI:33232";
    final ChebiOntology APPLICATION_ONTOLOGY = ontologyMap.get(APPLICATION_CHEBI_ID);
    int currentIndex = 0;

    /*
     * Compute the set of main applications. These are the ontologies that are subtypes of the ontology 'application'.
     */
    HashMap<ChebiOntology, HashSet<ChebiOntology>> isSubtypeOfRelationships =
        fetchIsSubtypeOfRelationships(brendaDB, ontologyMap);
    final HashSet<ChebiOntology> MAIN_APPLICATIONS_ONTOLOGIES = isSubtypeOfRelationships.get(APPLICATION_ONTOLOGY);

    /*
     * For each application, compute its set of main applications (subset of direct or indirect parents that are in
     * MAIN_APPLICATIONS_ONTOLOGIES) and store it in a hashmap applicationToMainApplicationsMap
     */
    HashMap<ChebiOntology, HashSet<ChebiOntology>> applicationToMainApplicationsMap = new HashMap<>();

    ArrayList<ChebiOntology> applicationsToVisit = new ArrayList<>(MAIN_APPLICATIONS_ONTOLOGIES);

    for (ChebiOntology applicationToVisit: applicationsToVisit) {
      HashSet<ChebiOntology> mainApplicationsSet = new HashSet<>();
      mainApplicationsSet.add(applicationToVisit);
      applicationToMainApplicationsMap.put(applicationToVisit, mainApplicationsSet);
    }

    while (currentIndex < applicationsToVisit.size()) {
      ChebiOntology currentApplication = applicationsToVisit.get(currentIndex);
      HashSet<ChebiOntology> subApplications = isSubtypeOfRelationships.get(currentApplication);
      if (subApplications != null) {
        applicationsToVisit.addAll(subApplications);
        for (ChebiOntology subApplication : subApplications) {
          HashSet<ChebiOntology> mainApplicationsSet = applicationToMainApplicationsMap.get(subApplication);
          if (mainApplicationsSet == null) {
            mainApplicationsSet = new HashSet<>();
            applicationToMainApplicationsMap.put(subApplication, mainApplicationsSet);
          }
          mainApplicationsSet.addAll(applicationToMainApplicationsMap.get(currentApplication));
        }
      }
      currentIndex++;
    }
    /*
     * Fetch the set of direct applications for each ontology that has a role (aka is a chemical entity).
     */
    HashMap<ChebiOntology, HashSet<ChebiOntology>> directApplicationMap =
        fetchHasRoleRelationships(brendaDB, ontologyMap, applicationsToVisit);

    /*
     * Compute the set of main applications for each ontology that has a role (aka is a chemical entity).
     */
    HashMap<ChebiOntology, HashSet<ChebiOntology>> chemicalEntityToMainApplicationMap = new HashMap<>();
    for (ChebiOntology chemicalEntity: directApplicationMap.keySet()) {
      HashSet<ChebiOntology> mainApplicationsSet = chemicalEntityToMainApplicationMap.get(chemicalEntity);
      if (mainApplicationsSet == null) {
        mainApplicationsSet = new HashSet<>();
        chemicalEntityToMainApplicationMap.put(chemicalEntity, mainApplicationsSet);
      }
      for (ChebiOntology parentApplication: directApplicationMap.get(chemicalEntity)) {
        HashSet<ChebiOntology> mainApplications = applicationToMainApplicationsMap.get(parentApplication);
        if (mainApplications != null) {
          mainApplicationsSet.addAll(mainApplications);
        }
      }
    }

    /*
     * Finally, construct a ChebiApplicationSet object containing direct and main applications for the molecules.
     */
    HashMap<ChebiOntology, ChebiApplicationSet> chemicalEntityToApplicationsMap = new HashMap<>();
    for (ChebiOntology chemicalEntity: directApplicationMap.keySet()) {
      ChebiApplicationSet applications = new ChebiApplicationSet(
          directApplicationMap.get(chemicalEntity),
          chemicalEntityToMainApplicationMap.get(chemicalEntity));
      chemicalEntityToApplicationsMap.put(chemicalEntity, applications);
    }

    return chemicalEntityToApplicationsMap;
  }

  /**
   * This function transforms the output of the above function to a human readable format.
   * @param chemicalEntityToApplicationsMap a map from ChebiOntology objects to a ChebiApplicationSet object
   * @return a simple map from a term to human readable lists of applications (main and direct)
   * @throws SQLException
   */
  public static HashMap<String, HashMap<String, ArrayList<String>>> getApplicationsSimple(
      HashMap<ChebiOntology, ChebiApplicationSet> chemicalEntityToApplicationsMap) throws SQLException {
    HashMap<String, HashMap<String, ArrayList<String>>> applicationMapSimple = new HashMap<>();
    for (ChebiOntology ontology: chemicalEntityToApplicationsMap.keySet()) {
      ArrayList<String> directApplications = new ArrayList<>();
      for (ChebiOntology directApplication: chemicalEntityToApplicationsMap.get(ontology).getDirectApplications()) {
        directApplications.add(directApplication.getTerm());
      }
      ArrayList<String> mainApplications = new ArrayList<>();
      for (ChebiOntology mainApplication: chemicalEntityToApplicationsMap.get(ontology).getMainApplications()) {
        mainApplications.add(mainApplication.getTerm());
      }
      HashMap<String, ArrayList<String>> applications = new HashMap<>();
      applications.put("directApplications", directApplications);
      applications.put("mainApplications", mainApplications);
      applicationMapSimple.put(ontology.getChebiId(), applications);
    }
    return applicationMapSimple;
  }

  public static void main(String[] args) throws SQLException {

    // Connect to the BRENDA DB
    SQLConnection brendaDB = new SQLConnection();
    brendaDB.connect("127.0.0.1", 3306, "brenda_user", "");

    // Get the ontology map (ChebiId -> ChebiOntology object)
    HashMap<String, ChebiOntology> ontologyMap = fetchOntologyMap(brendaDB);

    // Get the applications for all chemical entities
    HashMap<ChebiOntology, ChebiApplicationSet> chemicalEntityToApplicationsMap = getApplications(
        brendaDB,
        ontologyMap);

    // Get a simple version of the application map
    HashMap<String, HashMap<String, ArrayList<String>>> applicationMapSimple = getApplicationsSimple(
        chemicalEntityToApplicationsMap);

    System.out.println(applicationMapSimple.get("CHEBI:46195"));

    // Disconnect from the BRENDA DB
    brendaDB.disconnect();
  }
}