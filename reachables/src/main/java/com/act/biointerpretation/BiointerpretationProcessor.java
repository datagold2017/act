package com.act.biointerpretation;

import act.server.NoSQLAPI;
import act.shared.Chemical;
import act.shared.Organism;
import act.shared.Reaction;
import act.shared.Seq;
import act.shared.helpers.MongoDBToJSON;
import act.shared.helpers.P;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public abstract class BiointerpretationProcessor {
  private static final Logger LOGGER = LogManager.getFormatterLogger(BiointerpretationProcessor.class);

  private NoSQLAPI api;
  private Map<Long, Long> oldChemIdToNewChemId = new HashMap<>();
  private Map<Long, String> newChemIdToInchi = new HashMap<>();
  private HashMap<Long, Long> organismMigrationMap = new HashMap<>();

  boolean initCalled = false;

  /**
   * Returns the name of this biointerpretation step.
   * @return A name string for logging.
   */
  public abstract String getName();

  public BiointerpretationProcessor(NoSQLAPI api) {
    this.api = api;
  }

  /**
   * Initializes this processing step.  Must be called before run().
   * @throws Exception
   */
  public abstract void init() throws Exception;

  /**
   * Subclasses should call this in their init() implementations to prevent an exception being thrown when run() is
   * called.  This prevents run attempts without initialization.
   *
   * This isn't the most elegant way of handling this (a factory or dependency injection would be better), but this is
   * quick, safe, and effective.
   */
  protected void markInitialized() {
    initCalled = true;
  }

  protected void failIfNotInitialized() {
    if (!initCalled) {
      String msg = String.format("run() called without initialization for biointerpretation processor '%s'", getName());
      LOGGER.error(msg);
      throw new RuntimeException(msg);
    }
  }

  /**
   * Runs the biointerpretation processing on the read DB data and writes it to the write DB.
   * @throws Exception
   */
  public void run() throws Exception { // TODO: throwing Exception is not good.  Can we refine this?
    failIfNotInitialized();

    LOGGER.debug("Starting %s", getName());
    long startTime = new Date().getTime();

    LOGGER.info("Processing chemicals");
    processChemicals();
    LOGGER.info("Done processing chemicals");
    afterProcessChemicals();
    LOGGER.info("Processing reactions");
    processReactions();
    LOGGER.info("Done processing reactions");
    afterProcessReactions();

    long endTime = new Date().getTime();
    LOGGER.debug(String.format("Time in seconds: %d", (endTime - startTime) / 1000));

    LOGGER.info("Done %s", getName());
  }

  /**
   * A hook that runs after all chemicals have been processed/migrated.  This is meant to
   * be overridden, as it does nothing by default.
   */
  protected void afterProcessChemicals() throws Exception {

  }

  /**
   * A hook that runs after all reactions have been processed/migrated.  This is meant to
   * be overridden, as it does nothing by default.
   */
  protected void afterProcessReactions() throws Exception {

  }

  protected NoSQLAPI getNoSQLAPI() {
    return this.api;
  }

  protected Map<Long, Long> getOldChemIdToNewChemId() {
    return this.oldChemIdToNewChemId;
  }

  protected Map<Long, String> getNewChemIdToInchi() {
    return this.newChemIdToInchi;
  }

  protected Long mapOldChemIdToNewId(Long oldChemId) {
    // TODO: maybe raise a runtime exception if the result is null?
    return this.oldChemIdToNewChemId.get(oldChemId);
  }

  protected String mapNewChemIdToInChI(Long newChemId) {
    return this.newChemIdToInchi.get(newChemId);
  }

  /**
   * Process and migrate reactions.  Default implementation merely copies, preserving source id.
   * @throws Exception
   */
  protected void processChemicals() throws Exception {
    Iterator<Chemical> chemicals = api.readChemsFromInKnowledgeGraph();
    while (chemicals.hasNext()) {
      // TODO: should we apply the blacklist here so everybody can benefit from it?
      Chemical chem = chemicals.next();
      Long oldId = chem.getUuid();
      chem = runSpecializedChemicalProcessing(chem);
      Long newId = api.writeToOutKnowlegeGraph(chem);
      // Cache the old-to-new id mapping so we don't have to hit the DB for each chemical.
      oldChemIdToNewChemId.put(oldId, newId);
      // Cache the id to InChI mapping so we don't have to re-load the chem documents just to get the InChI.
      newChemIdToInchi.put(newId, chem.getInChI());
    }
  }

  /**
   * A hook that runs after the reaction's chemicals and proteins have been prepped for writing.  This is meant to
   * be overridden, as it does nothing by default.
   * @param chem The chem object about to be written.
   * @return The modified reaction.
   */
  protected Chemical runSpecializedChemicalProcessing(Chemical chem) {
    return chem;
  }

  /**
   * Process and migrate reactions.  Default implementation merely copies, preserving source id.
   * @throws Exception
   */
  protected void processReactions() throws Exception {
    //Scan through all Reactions and process each
    Iterator<Reaction> iterator = api.readRxnsFromInKnowledgeGraph();

    while (iterator.hasNext()) {
      // Get reaction from the read db
      Reaction oldRxn = iterator.next();
      Long oldId = Long.valueOf(oldRxn.getUUID());

      oldRxn = preProcessReaction(oldRxn);

      Reaction newRxn = new Reaction(
          -1, // Assume the id will be set when the reaction is written to the DB.
          new Long[0],
          new Long[0],
          new Long[0],
          new Long[0],
          new Long[0],
          oldRxn.getECNum(),
          oldRxn.getConversionDirection(),
          oldRxn.getPathwayStepDirection(),
          oldRxn.getReactionName(),
          oldRxn.getRxnDetailType()
      );

      // Add the data source and references from the source to the destination
      newRxn.setDataSource(oldRxn.getDataSource());
      for (P<Reaction.RefDataSource, String> ref : oldRxn.getReferences()) {
        newRxn.addReference(ref.fst(), ref.snd());
      }

      int newId = api.writeToOutKnowlegeGraph(newRxn);
      Long newIdL = Long.valueOf(newId);

      migrateReactionChemicals(newRxn, oldRxn);
      migrateAllProteins(newRxn, newIdL, oldRxn, oldId);

      // Give the subclasses a chance at the reactions.
      newRxn = runSpecializedReactionProcessing(newRxn);

      // Update the reaction in the DB with the newly migrated protein data.
      api.getWriteDB().updateActReaction(newRxn, newId);
    }
  }

  /**
   * A hook that runs on the reaction from the read DB before it's written to the write DB.  This is meant to
   * be overridden, as it does nothing by default.
   *
   * Return an original or modified reaction to be migrated to the DB, or return null to have this reaction skipped.
   *
   * @param rxn The reaction object from the read DB.
   * @return The modified reaction or null if nothing should be written to the DB.
   */
  protected Reaction preProcessReaction(Reaction rxn) {
    return rxn;
  }

  /**
   * A hook that runs after the reaction's chemicals and proteins have been prepped for writing.  This is meant to
   * be overridden, as it does nothing by default.
   * @param rxn The reaction object about to be written.
   * @return The modified reaction.
   */
  protected Reaction runSpecializedReactionProcessing(Reaction rxn) {
    return rxn;
  }

  /**
   * Migrates all protein data from oldRxn to newRxn, preserving the source reaction id on the protein objects.
   * @param newRxn The reaction to which to write protein data.
   * @param newId The new reaction's ID (taken as a parameter due to MongoDB.java limitations).
   * @param oldRxn The reaction from which to read protein data.
   * @param oldId The old reaction's ID (taken as a parameter for symmetry with newId).
   */
  protected void migrateAllProteins(Reaction newRxn, Long newId, Reaction oldRxn, Long oldId) {
    for (JSONObject protein : oldRxn.getProteinData()) {
      JSONObject newProteinData = migrateProteinData(protein, newId, oldRxn);
      // Save the source reaction ID for debugging/verification purposes.  TODO: is adding a field like this okay?
      newProteinData.put("source_reaction_id", oldId);
      newRxn.addProteinData(newProteinData);
    }
  }


  /**
   * Default implementation just copies chemicals, cofactors, and coefficients.
   * @param newRxn The new Reaction object to be written into the write DB.
   * @param oldRxn The old Reaction object read from the read DB.
   */
  protected void migrateReactionChemicals(Reaction newRxn, Reaction oldRxn) {
    // TODO: this has been written/re-written too many times.  Lift this into a shared superclass.
    Long[] oldSubstrates = oldRxn.getSubstrates();
    Long[] oldProducts = oldRxn.getProducts();
    List<Long> migratedSubstrates = new ArrayList<>(Arrays.asList(oldSubstrates).stream().map(oldChemIdToNewChemId::get).
        filter(x -> x != null).collect(Collectors.toList()));
    List<Long> migratedProducts = new ArrayList<>(Arrays.asList(oldProducts).stream().map(oldChemIdToNewChemId::get).
        filter(x -> x != null).collect(Collectors.toList()));

    // Substrate/product counts must be identical before and after migration.
    if (migratedSubstrates.size() != oldSubstrates.length ||
        migratedProducts.size() != oldProducts.length) {
      throw new RuntimeException(String.format(
          "Pre/post substrate/product migration lengths don't match for source reaction %d: %d -> %d, %d -> %d",
          oldRxn.getUUID(), oldSubstrates.length, migratedSubstrates.size(), oldProducts.length, migratedProducts.size()
      ));
    }

    newRxn.setSubstrates(migratedSubstrates.toArray(new Long[migratedSubstrates.size()]));
    newRxn.setProducts(migratedProducts.toArray(new Long[migratedProducts.size()]));

    // Copy over substrate/product coefficients one at a time based on index, which should be consistent.
    for (int i = 0; i < migratedSubstrates.size(); i++) {
      newRxn.setSubstrateCoefficient(migratedSubstrates.get(i), oldRxn.getSubstrateCoefficient(oldSubstrates[i]));
    }

    for (int i = 0; i < migratedProducts.size(); i++) {
      newRxn.setProductCoefficient(migratedProducts.get(i), oldRxn.getProductCoefficient(oldProducts[i]));
    }

    Long[] oldSubstrateCofactors = oldRxn.getSubstrateCofactors();
    Long[] oldProductCofactors = oldRxn.getProductCofactors();

    List<Long> migratedSubstrateCofactors =
        Arrays.asList(oldSubstrateCofactors).stream().map(oldChemIdToNewChemId::get).
            filter(x -> x != null).collect(Collectors.toList());
    List<Long> migratedProductCofactors =
        Arrays.asList(oldProductCofactors).stream().map(oldChemIdToNewChemId::get).
            filter(x -> x != null).collect(Collectors.toList());

    if (migratedSubstrateCofactors.size() != oldSubstrateCofactors.length ||
        migratedProductCofactors.size() != oldProductCofactors.length) {
      throw new RuntimeException(String.format(
          "Pre/post sub/prod cofactor migration lengths don't match for source reaction %d: %d -> %d, %d -> %d",
          oldRxn.getUUID(), oldSubstrateCofactors.length, migratedSubstrateCofactors.size(),
          oldProductCofactors.length, migratedProductCofactors.size()
      ));
    }

    newRxn.setSubstrateCofactors(migratedSubstrateCofactors.toArray(new Long[migratedSubstrateCofactors.size()]));
    newRxn.setProductCofactors(migratedProductCofactors.toArray(new Long[migratedProductCofactors.size()]));
  }

  protected void

  // Cache seen organism ids locally to speed up migration.
  private Long migrateOrganism(Long oldOrganismId) {
    if (organismMigrationMap.containsKey(oldOrganismId)) {
      return organismMigrationMap.get(oldOrganismId);
    }

    String organismName = api.getReadDB().getOrganismNameFromId(oldOrganismId);

    Long newOrganismId = null;

    // Assume any valid organism entry will have a name.
    if (organismName != null) {
      // TODO: reading from the writeDB is not so good, but we need to not insert twice.  Is there a better way?
      long writeDBOrganismId = api.getWriteDB().getOrganismId(organismName);
      if (writeDBOrganismId != -1) { // -1 is used in MongoDB.java for missing values.
        // Reuse the existing organism entry if we can find a matching one.
        newOrganismId = writeDBOrganismId;
      } else {
        // Use -1 for no NCBI Id.  Note that the NCBI parameter isn't even stored in the DB at present.
        Organism newOrganism = new Organism(oldOrganismId, -1, organismName);
        api.getWriteDB().submitToActOrganismNameDB(newOrganism);
        newOrganismId = newOrganism.getUUID();
      }

    }

    organismMigrationMap.put(oldOrganismId, newOrganismId);

    return newOrganismId;
  }

  protected JSONObject migrateProteinData(JSONObject oldProtein, Long newRxnId, Reaction rxn) {
    // Copy the protein object for modification.
    // With help from http://stackoverflow.com/questions/12809779/how-do-i-clone-an-org-json-jsonobject-in-java.
    JSONObject newProtein = new JSONObject(oldProtein, JSONObject.getNames(oldProtein));

    /* Metacyc entries write an array of NCBI organism ids per protein, but do not reference organism name collection
     * entries.  Only worry about the "organism" field, which refers to the ID of an organism name entry. */
    if (oldProtein.has("organism")) {
      // BRENDA protein entries just have one organism, so the migration is a little easier.
      Long oldOrganismId = oldProtein.getLong("organism");
      Long newOrganismId = migrateOrganism(oldOrganismId);
      newProtein.put("organism", newOrganismId);
    }
    // TODO: unify the Protein object schema so this sort of handling isn't necessary.

    Set<Long> rxnIds = Collections.singleton(newRxnId);

    // Can't use Collections.singletonMap, as MongoDB expects a HashMap explicitly.
    HashMap<Long, Set<Long>> rxnToSubstrates = new HashMap<>(1);
    // With help from http://stackoverflow.com/questions/3064423/in-java-how-to-easily-convert-an-array-to-a-set.
    rxnToSubstrates.put(newRxnId, new HashSet<>(Arrays.asList(rxn.getSubstrates())));

    HashMap<Long, Set<Long>> rxnToProducts = new HashMap<>(1);
    rxnToProducts.put(newRxnId, new HashSet<>(Arrays.asList(rxn.getProducts())));

    JSONArray sequences = oldProtein.getJSONArray("sequences");
    List<Long> newSequenceIds = new ArrayList<>(sequences.length());
    for (int i = 0; i < sequences.length(); i++) {
      Long sequenceId = sequences.getLong(i);
      Seq seq = api.getReadDB().getSeqFromID(sequenceId);

      // Assumption: seq refer to exactly one reaction.
      if (seq.getReactionsCatalyzed().size() > 1) {
        throw new RuntimeException(String.format(
            "Assumption violation: sequence with source id %d refers to %d reactions (>1).  " +
                "Merging will not handly this case correctly.\n", seq.getUUID(), seq.getReactionsCatalyzed().size()));
      }

      Long oldSeqOrganismId = seq.getOrgId();
      Long newSeqOrganismId = migrateOrganism(oldSeqOrganismId);

      // Store the seq document to get an id that'll be stored in the protein object.
      int seqId = api.getWriteDB().submitToActSeqDB(
          seq.get_srcdb(),
          seq.get_ec(),
          seq.get_org_name(),
          newSeqOrganismId, // Use freshly migrated organism id to replace the old one.
          seq.get_sequence(),
          seq.get_references(),
          rxnIds, // Use the reaction's new id (also in substrates/products) instead of the old one.
          rxnToSubstrates,
          rxnToProducts,
          seq.getCatalysisSubstratesUniform(), // These should not have changed due to the migration.
          seq.getCatalysisSubstratesDiverse(),
          seq.getCatalysisProductsUniform(),
          seq.getCatalysisProductsDiverse(),
          seq.getSAR(),
          MongoDBToJSON.conv(seq.get_metadata())
      );
      // TODO: we should migrate all the seq documents with zero references over to the new DB.

      // Convert to Long to match ID type seen in MongoDB.  TODO: clean up all the IDs, make them all Longs.
      newSequenceIds.add(Long.valueOf(seqId));
    }
    // Store the migrated sequence ids for this protein.
    newProtein.put("sequences", new JSONArray(newSequenceIds));

    return newProtein;
  }

}
