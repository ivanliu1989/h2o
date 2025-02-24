package hex.gbm;

import water.api.AUCData;
import static water.util.MRUtils.sampleFrameStratified;
import static water.util.ModelUtils.getPrediction;
import hex.ConfusionMatrix;
import hex.VarImp;
import hex.drf.DRF;
import hex.rng.MersenneTwisterRNG;
import jsr166y.CountedCompleter;
import water.*;
import water.H2O.H2OCountedCompleter;
import water.Job.ValidatedJob;
import water.api.AUC;
import water.api.DocGen;
import water.api.ParamImportance;
import water.fvec.Chunk;
import water.fvec.Frame;
import water.fvec.Vec;
import water.util.Log;
import water.util.Log.Tag.Sys;
import water.util.MRUtils;
import water.util.ModelUtils;
import water.util.Utils;

import java.util.Arrays;
import java.util.Random;

/**
 *  Shared (distributed) trees builder.
 *
 *  <p>Used for both <em>Gradient Boosted Method</em> (see {@link GBM}) and <em>Random
 *  Forest</em> (see {@link DRF}), and really could be used for any decision-tree builder.</p>
 *
 *  <p>While this is a wholly H<sub>2</sub>O-design, we found these papers afterwards that
 *  describes our design fairly well:</p>
 * <ul>
 *  <li><a href="http://www.cse.wustl.edu/~kilian/papers/fr819-tyreeA.pdf">Parallel GBRT</a></li>
 *  <li><a href="http://jmlr.org/papers/volume11/ben-haim10a/ben-haim10a.pdf">Streaming parallel decision tree</a></li>
 * </ul>
 *
 * <p>Note that our <em>dynamic histogram</em> technique is different (surely faster, and
 * probably less mathematically clean).  I'm sure a host of other smaller details
 * differ also - but in the Big Picture the paper and our algorithm are similar.</p>
*/
public abstract class SharedTreeModelBuilder<TM extends DTree.TreeModel> extends ValidatedJob {
  static final int API_WEAVER = 1; // This file has auto-gen'd doc & json fields
  static public DocGen.FieldDoc[] DOC_FIELDS; // Initialized from Auto-Gen code.

  @API(help = "Number of trees. Grid Search, comma sep values:50,100,150,200", filter = Default.class, lmin=1, lmax=1000000, json=true, importance=ParamImportance.CRITICAL)
  public int ntrees = 50;

  @API(help = "Maximum tree depth. Grid Search, comma sep values:5,7", filter = Default.class, lmin=1, lmax=10000, json=true, importance=ParamImportance.CRITICAL)
  public int max_depth = 5;

  @API(help = "Fewest allowed observations in a leaf (in R called 'nodesize'). Grid Search, comma sep values", filter = Default.class, lmin=1, json=true, importance=ParamImportance.SECONDARY)
  public int min_rows = 10;

  @API(help = "Build a histogram of this many bins, then split at the best point", filter = Default.class, lmin=2, lmax=10000, json=true, importance=ParamImportance.SECONDARY)
  public int nbins = 20;

  @API(help = "Perform scoring after each iteration (can be slow)", filter = Default.class, json=true)
  public boolean score_each_iteration = false;

  @API(help = "Compute variable importance (true/false).", filter = Default.class )
  protected boolean importance = false; // compute variable importance

  /**
   * For imbalanced data, balance training data class counts via
   * over/under-sampling. This can result in improved predictive accuracy.
   */
  @API(help = "Balance training data class counts via over/under-sampling (for imbalanced data)", filter = Default.class, json = true, importance = ParamImportance.EXPERT)
  public boolean balance_classes = false;

  /**
   * When classes are balanced, limit the resulting dataset size to the
   * specified multiple of the original dataset size.
   */
  @API(help = "Maximum relative size of the training data after balancing class counts (can be less than 1.0)", filter = Default.class, json = true, dmin=1e-3, importance = ParamImportance.EXPERT)
  public float max_after_balance_size = Float.POSITIVE_INFINITY;

  @API(help = "Model checkpoint to start building a new model from", filter = Default.class, json = true, required = false)
  public Key checkpoint;

  @API(help = "Overwrite checkpoint", filter = Default.class, json = true, required = false)
  public boolean overwrite_checkpoint = true;

//  @API(help = "Active feature columns")
  protected int _ncols;

//  @API(help = "Rows in training dataset")
  protected long _nrows;

//  @API(help = "Number of classes")
  protected int _nclass;

  @API(help = "Class distribution")
  protected long _distribution[];

  // Distribution of classes in response
  protected float[] _priorClassDist = null;
  // New distribution of classes if input frame was modified (resampled, balanced)
  protected float[] _modelClassDist = null;

  // Number of trees inherited from checkpoint
  protected int _ntreesFromCheckpoint;

  /** Maximal number of supported levels in response. */
  public static final int MAX_SUPPORTED_LEVELS = 1000;

  /** Marker for already decided row. */
  static public final int DECIDED_ROW = -1;
  /** Marker for sampled out rows */
  static public final int OUT_OF_BAG = -2;

  @Override public float progress(){
    Value value = DKV.get(dest());
    DTree.TreeModel m = value != null ? (DTree.TreeModel) value.get() : null;
    return m == null ? 0 : cv_progress(m.ntrees() / (float) m.N);
  }

  // Verify input parameters
  @Override protected void init() {
    super.init();
    // Check parameters
    assert 0 <= ntrees && ntrees < 1000000; // Sanity check
    // Should be handled by input
    //assert response.isEnum() : "Response is not enum";
    assert (classification && (response.isInt() || response.isEnum())) ||   // Classify Int or Enums
           (!classification && !response.isEnum()) : "Classification="+classification + " and response="+response.isInt();  // Regress  Int or Float

    if (source.numRows() - response.naCnt() <=0)
      throw new IllegalArgumentException("Dataset contains too many NAs!");

    _ncols = _train.length;
    _nrows = source.numRows() - response.naCnt();

    assert (_nrows>0) : "Dataset contains no rows - validation of input parameters is probably broken!";
    // Transform response to enum
    // TODO: moved to shared model job
    if( !response.isEnum() && classification ) {
      response = response.toEnum();
      gtrash(response); //_gen_enum = true;
    }
    _nclass = response.isEnum() ? (char)(response.domain().length) : 1;
    if (classification && _nclass <= 1)
      throw new IllegalArgumentException("Constant response column!");
    if (_nclass > MAX_SUPPORTED_LEVELS)
      throw new IllegalArgumentException("Too many levels in response column!");

    int usableColumns = 0;
    assert _ncols == _train.length : "Number of selected train columns does not correspond to a number of columns!";
    for (int i = 0; i < _ncols; i++) {
      Vec v = _train[i];
      if (v.isBad() || v.isConst()) continue;
      usableColumns++;
    }
    if (usableColumns==0) throw new IllegalArgumentException("There is no usable column to generate model!");

    if (checkpoint!=null && DKV.get(checkpoint)==null) throw new IllegalArgumentException("Checkpoint "+checkpoint.toString() + " does not exists!");
  }

  @Override protected Key defaultDestKey() {
    if (checkpoint!=null && overwrite_checkpoint)
      return checkpoint;
    else
      return super.defaultDestKey();
  }

  // --------------------------------------------------------------------------
  // Driver for model-building.
  public void buildModel(long seed) {
    final Key outputKey = dest();
    final Key dataKey = source != null ? source._key : null;
    final Key testKey = validation != null ? validation._key : dataKey;

    // Lock the input datasets against deletes
    source.read_lock(self());
    if( validation != null && !source._key.equals(validation._key) )
      validation.read_lock(self());

    // Prepare a frame for this tree algorithm run
    Frame fr = new Frame(_names, _train);
    fr.add(_responseName,response);
    final Frame frm = new Frame(fr); // Model-Frame; no extra columns
    String names[] = frm.names();
    String domains[][] = frm.domains();

    // For doing classification on Integer (not Enum) columns, we want some
    // handy names in the Model.  This really should be in the Model code.
    String[] domain = response.domain();
    if( domain == null && _nclass > 1 ) // No names?  Something is wrong since we converted response to enum already !
      assert false : "Response domain' names should be always presented in case of classification";
    if( domain == null ) domain = new String[] {"r"}; // For regression, give a name to class 0

    // Compute class distribution
    if (classification) {
      MRUtils.ClassDist cdmt = new MRUtils.ClassDist(_nclass).doAll(response);
      _distribution = cdmt.dist();
      _priorClassDist = cdmt.rel_dist();
    }

    // Handle imbalanced classes by stratified over/under-sampling
    // initWorkFrame sets the modeled class distribution, and model.score() corrects the probabilities back using the distribution ratios
    float[] trainSamplingFactors;
    if (classification && balance_classes) {
      int response_idx = fr.find(_responseName);
      trainSamplingFactors = new float[domain.length]; //leave initialized to 0 -> will be filled up below
      Frame stratified = sampleFrameStratified(
              fr, fr.lastVec(), trainSamplingFactors, (long)(max_after_balance_size*fr.numRows()), seed, true, false);
      if (stratified != fr) {
        fr = stratified;
        _nrows = fr.numRows();
        response = fr.vecs()[response_idx];
        // Recompute distribution since the input frame was modified
        MRUtils.ClassDist cdmt = new MRUtils.ClassDist(_nclass).doAll(response);
        _distribution = cdmt.dist();
        _modelClassDist = cdmt.rel_dist();
        gtrash(stratified);
      }
    }
    Log.info(logTag(), "Prior class distribution: " + Arrays.toString(_priorClassDist));
    Log.info(logTag(), "Model class distribution: " + Arrays.toString(_modelClassDist));

    // Also add to the basic working Frame these sets:
    //   nclass Vecs of current forest results (sum across all trees)
    //   nclass Vecs of working/temp data
    //   nclass Vecs of NIDs, allowing 1 tree per class

    // Current forest values: results of summing the prior M trees
    for( int i=0; i<_nclass; i++ )
      fr.add("Tree_"+domain[i], response.makeZero());

    // Initial work columns.  Set-before-use in the algos.
    for( int i=0; i<_nclass; i++ )
      fr.add("Work_"+domain[i], response.makeZero());

    // One Tree per class, each tree needs a NIDs.  For empty classes use a -1
    // NID signifying an empty regression tree.
    for( int i=0; i<_nclass; i++ )
      fr.add("NIDs_"+domain[i], response.makeCon(_distribution==null ? 0 : (_distribution[i]==0?-1:0)));

    // Timer  for model building
    Timer bm_timer =  new Timer();
    long before = System.currentTimeMillis();
    // Fetch checkpoint
    assert checkpoint==null || (!(overwrite_checkpoint && checkpoint!=null) || outputKey==checkpoint): "If checkpoint is to be overwritten then outputkey has to equal to checkpoint key";
    TM checkpointModel = checkpoint!=null ? (TM) UKV.get(checkpoint) : null;
    // Create an INITIAL MODEL based on given parameters
    TM model = makeModel(outputKey, dataKey, testKey, checkpointModel!=null?ntrees+checkpointModel.ntrees():ntrees,names, domains, getCMDomain(), _priorClassDist, _modelClassDist);
    // Update the model by a checkpoint
    if (checkpointModel!=null) {
      checkpointModel.read_lock(self()); // lock it for read to avoid any other job to start working on it
      try {
        // Create a new initial model based on given checkpoint
        // TODO: check compatibility of parameters !
        model = updateModel(model, checkpointModel, overwrite_checkpoint);
        _ntreesFromCheckpoint = checkpointModel.ntrees();
      } finally { checkpointModel.unlock(self()); }
    }

    // Save the model ! (delete_and_lock has side-effect of saving model into DKV)
    if (checkpoint!=null && overwrite_checkpoint)
      model.write_lock(self()); // do not delete previous model since it would trigger delete of stored trees which we need
    else
      model.delete_and_lock(self()); // we can safely delete any previous model since this one should be the first one
    // Prepare and cache adapted validation dataset if it is necessary
    prepareValidationWithModel(model);

    try {
      // Initialized algorithm
      initAlgo(model);
      // Init working frame
      initWorkFrame(model, fr);
      // Compute the model
      model = buildModel(model, fr, names, domains, bm_timer);
    //} catch (Throwable t) { t.printStackTrace();
    } finally {
      model.unlock(self());  // Update and unlock model
      cleanUp(fr,bm_timer);  // Shared cleanup
      model.start_training(before);
      model.stop_training();
    }
  }

  // Tree model cleanup
  protected void cleanUp(Frame fr, Timer t_build) {
    //super.cleanUp(fr, t_build);
    Log.info(logTag(),"Modeling done in "+t_build);

    // Remove temp vectors; cleanup the Frame
    while( fr.numCols() > _ncols+1/*Do not delete the response vector*/ )
      UKV.remove(fr.remove(fr.numCols()-1)._key);

    // Unlock the input datasets against deletes
    source.unlock(self());
    if( validation != null && !source._key.equals(validation._key) )
      validation.unlock(self());
  }

  transient long _timeLastScoreStart, _timeLastScoreEnd, _firstScore;
  protected TM doScoring(TM model, Frame fTrain, DTree[] ktrees, int tid, DTree.TreeModel.TreeStats tstats, boolean finalScoring, boolean oob, boolean build_tree_one_node ) {
    long now = System.currentTimeMillis();
    if( _firstScore == 0 ) _firstScore=now;
    long sinceLastScore = now-_timeLastScoreStart;
    Score sc = null;
    // If validation is specified we use a model for scoring, so we need to update it!
    // First we save model with trees (i.e., make them available for scoring)
    // and then update it with resulting error
    model = makeModel(model, ktrees, tstats);
    model.update(self());
    // Now model already contains tid-trees in serialized form
    if( score_each_iteration ||
        finalScoring ||
        (now-_firstScore < 4000) || // Score every time for 4 secs
        // Throttle scoring to keep the cost sane; limit to a 10% duty cycle & every 4 secs
        (sinceLastScore > 4000 && // Limit scoring updates to every 4sec
         (double)(_timeLastScoreEnd-_timeLastScoreStart)/sinceLastScore < 0.1) ) { // 10% duty cycle
      _timeLastScoreStart = now;
      // Perform scoring - first get adapted validation response
      Response2CMAdaptor vadaptor = getValidAdaptor();
      sc = new Score().doIt(model, fTrain, vadaptor, oob, build_tree_one_node).report(logTag(),tid,ktrees);
      _timeLastScoreEnd = System.currentTimeMillis();
    }

    // Compute variable importance for this tree if necessary
    VarImp varimp = null;
    if (importance && ktrees!=null) { // compute this tree votes but skip the first scoring call which is done over empty forest
      Timer vi_timer = new Timer();
      varimp  = doVarImpCalc(model, ktrees, tid-1, fTrain, false);
      Log.info(logTag(), "Computation of variable importance with "+tid+"th-tree took: " + vi_timer.toString());
    }
    // Double update - after scoring
    model = makeModel(model,
                      sc==null ? Double.NaN : sc.mse(),
                      sc==null ? null : (_nclass>1? new ConfusionMatrix(sc._cm):null),
                      varimp,
                      sc==null ? null : (_nclass==2 ? makeAUC(toCMArray(sc._cms), ModelUtils.DEFAULT_THRESHOLDS) : null)
                      );
    model.update(self());
    return model;
  }

  protected abstract VarImp doVarImpCalc(TM model, DTree[] ktrees, int tid, Frame validationFrame, boolean scale);

  private ConfusionMatrix[] toCMArray(long[][][] cms) {
    int n = cms.length;
    ConfusionMatrix[] res = new ConfusionMatrix[n];
    for (int i = 0; i < n; i++) res[i] = new ConfusionMatrix(cms[i]);
    return res;
  }

  public boolean supportsBagging() { return false; }

  // --------------------------------------------------------------------------
  // Convenvience accessor for a complex chunk layout.
  // Wish I could name the array elements nicer...
  protected Chunk chk_resp( Chunk chks[]        ) { return chks[_ncols]; }
  protected Chunk chk_tree( Chunk chks[], int c ) { return chks[_ncols+1+c]; }
  protected Chunk chk_work( Chunk chks[], int c ) { return chks[_ncols+1+_nclass+c]; }
  protected Chunk chk_nids( Chunk chks[], int t ) { return chks[_ncols+1+_nclass+_nclass+t]; }
  // Out-of-bag trees counter - only one since it is shared via k-trees
  protected Chunk chk_oobt(Chunk chks[]) { return chks[_ncols+1+_nclass+_nclass+_nclass]; }

  protected final Vec vec_nids( Frame fr, int t) { return fr.vecs()[_ncols+1+_nclass+_nclass+t]; }
  protected final Vec vec_resp( Frame fr, int t) { return fr.vecs()[_ncols]; }
  protected final Vec vec_tree( Frame fr, int c ) { return fr.vecs()[_ncols+1+c]; }

  protected double[] data_row( Chunk chks[], int row, double[] data) {
    assert data.length == _ncols;
    for(int f=0; f<_ncols; f++) data[f] = chks[f].at0(row);
    return data;
  }

  // --------------------------------------------------------------------------
  // Fuse 2 conceptual passes into one:
  //
  // Pass 1: Score a prior partially-built tree model, and make new Node
  //         assignments to every row.  This involves pulling out the current
  //         assigned DecidedNode, "scoring" the row against that Node's
  //         decision criteria, and assigning the row to a new child
  //         UndecidedNode (and giving it an improved prediction).
  //
  // Pass 2: Build new summary DHistograms on the new child UndecidedNodes
  //         every row got assigned into.  Collect counts, mean, variance, min,
  //         max per bin, per column.
  //
  // The result is a set of DHistogram arrays; one DHistogram array for
  // each unique 'leaf' in the tree being histogramed in parallel.  These have
  // node ID's (nids) from 'leaf' to 'tree._len'.  Each DHistogram array is
  // for all the columns in that 'leaf'.
  //
  // The other result is a prediction "score" for the whole dataset, based on
  // the previous passes' DHistograms.
  public class ScoreBuildHistogram extends MRTask2<ScoreBuildHistogram> {
    final int   _k;    // Which tree
    final DTree _tree; // Read-only, shared (except at the histograms in the Nodes)
    final int   _leaf; // Number of active leaves (per tree)
    // Histograms for every tree, split & active column
    final DHistogram _hcs[/*tree-relative node-id*/][/*column*/];
    final boolean _subset;      // True if working a subset of cols
    public ScoreBuildHistogram(H2OCountedCompleter cc, int k, DTree tree, int leaf, DHistogram hcs[][], boolean subset) {
      super(cc);
      _k   = k;
      _tree= tree;
      _leaf= leaf;
      _hcs = hcs;
      _subset = subset;
    }

    // Once-per-node shared init
    @Override public void setupLocal( ) {
      // Init all the internal tree fields after shipping over the wire
      _tree.init_tree();
      // Allocate local shared memory histograms
      for( int l=_leaf; l<_tree._len; l++ ) {
        DTree.UndecidedNode udn = _tree.undecided(l);
        DHistogram hs[] = _hcs[l-_leaf];
        int sCols[] = udn._scoreCols;
        if( sCols != null ) { // Sub-selecting just some columns?
          for( int j=0; j<sCols.length; j++) // For tracked cols
            hs[sCols[j]].init();
        } else {                // Else all columns
          for( int j=0; j<_ncols; j++) // For all columns
            if( hs[j] != null )        // Tracking this column?
              hs[j].init();
        }
      }
    }

    @Override public void map( Chunk[] chks ) {
      assert chks.length==_ncols+4;
      final Chunk tree = chks[_ncols+1];
      final Chunk wrks = chks[_ncols+2];
      final Chunk nids = chks[_ncols+3];

      // Pass 1: Score a prior partially-built tree model, and make new Node
      // assignments to every row.  This involves pulling out the current
      // assigned DecidedNode, "scoring" the row against that Node's decision
      // criteria, and assigning the row to a new child UndecidedNode (and
      // giving it an improved prediction).
      int nnids[] = new int[nids._len];
      if( _leaf > 0)            // Prior pass exists?
        score_decide(chks,nids,wrks,tree,nnids);
      else                      // Just flag all the NA rows
        for( int row=0; row<nids._len; row++ )
          if( isDecidedRow((int)nids.at0(row)) ) nnids[row] = -1;

      // Pass 2: accumulate all rows, cols into histograms
      if( _subset ) accum_subset(chks,nids,wrks,nnids);
      else          accum_all   (chks,     wrks,nnids);
    }

    @Override public void reduce( ScoreBuildHistogram sbh ) {
      // Merge histograms
      if( sbh._hcs == _hcs ) return; // Local histograms all shared; free to merge
      // Distributed histograms need a little work
      for( int i=0; i<_hcs.length; i++ ) {
        DHistogram hs1[] = _hcs[i], hs2[] = sbh._hcs[i];
        if( hs1 == null ) _hcs[i] = hs2;
        else if( hs2 != null )
          for( int j=0; j<hs1.length; j++ )
            if( hs1[j] == null ) hs1[j] = hs2[j];
            else if( hs2[j] != null )
              hs1[j].add(hs2[j]);
      }
    }

    // Pass 1: Score a prior partially-built tree model, and make new Node
    // assignments to every row.  This involves pulling out the current
    // assigned DecidedNode, "scoring" the row against that Node's decision
    // criteria, and assigning the row to a new child UndecidedNode (and
    // giving it an improved prediction).
    private void score_decide(Chunk chks[], Chunk nids, Chunk wrks, Chunk tree, int nnids[]) {
      for( int row=0; row<nids._len; row++ ) { // Over all rows
        int nid = (int)nids.at80(row);         // Get Node to decide from
        if( isDecidedRow(nid)) {               // already done
          nnids[row] = (nid-_leaf);
          continue;
        }
        // Score row against current decisions & assign new split
        boolean oob = isOOBRow(nid);
        if( oob ) nid = oob2Nid(nid); // sampled away - we track the position in the tree
        DTree.DecidedNode dn = _tree.decided(nid);
        if (dn._split._col == -1 && DTree.isRootNode(dn)) { nnids[row] = (nid-_leaf); continue; }
        if( dn._split._col == -1 ) { // Might have a leftover non-split
          nid = dn._pid;             // Use the parent split decision then
          int xnid = oob ? nid2Oob(nid) : nid;
          nids.set0(row, xnid);
          nnids[row] = xnid-_leaf;
          dn = _tree.decided(nid); // Parent steers us
        }

        assert !isDecidedRow(nid);
        nid = dn.ns(chks,row); // Move down the tree 1 level
        if( !isDecidedRow(nid) ) {
          int xnid = oob ? nid2Oob(nid) : nid;
          nids.set0(row, xnid);
          nnids[row] = xnid-_leaf;
        } else {
          nnids[row] = nid-_leaf;
        }
      }
    }

    // All rows, some cols, accumulate histograms
    private void accum_subset(Chunk chks[], Chunk nids, Chunk wrks, int nnids[]) {
      for( int row=0; row<nnids.length; row++ ) { // Over all rows
        int nid = nnids[row];                     // Get Node to decide from
        if( nid >= 0 ) {        // row already predicts perfectly or OOB
          assert !Double.isNaN(wrks.at0(row)); // Already marked as sampled-away
          DHistogram nhs[] = _hcs[nid];
          int sCols[] = _tree.undecided(nid+_leaf)._scoreCols; // Columns to score (null, or a list of selected cols)
          for( int j=0; j<sCols.length; j++) { // For tracked cols
            final int c = sCols[j];
            nhs[c].incr((float)chks[c].at0(row),wrks.at0(row)); // Histogram row/col
          }
        }
      }
    }

    // All rows, all cols, accumulate histograms.  This is the hot hot inner
    // loop of GBM, so we do some non-standard optimizations.  The rows in this
    // chunk are spread out amongst a modest set of NodeIDs/splits.  Normally
    // we would visit the rows in row-order, but this visits the NIDs in random
    // order.  The hot-part of this code updates the histograms racily (via
    // atomic updates) - once-per-row.  This optimized version updates the
    // histograms once-per-NID, but requires pre-sorting the rows by NID.
    private void accum_all(Chunk chks[], Chunk wrks, int nnids[]) {
      final DHistogram hcs[][] = _hcs;
      // Sort the rows by NID, so we visit all the same NIDs in a row
      // Find the count of unique NIDs in this chunk
      int nh[] = new int[hcs.length+1];
      for( int i : nnids ) if( i >= 0 ) nh[i+1]++;
      // Rollup the histogram of rows-per-NID in this chunk
      for( int i=0; i<hcs.length; i++ ) nh[i+1] += nh[i];
      // Splat the rows into NID-groups
      int rows[] = new int[nnids.length];
      for( int row=0; row<nnids.length; row++ )
        if( nnids[row] >= 0 )
          rows[nh[nnids[row]]++] = row;
      // rows[] has Chunk-local ROW-numbers now, in-order, grouped by NID.
      // nh[] lists the start of each new NID, and is indexed by NID+1.
      accum_all2(chks,wrks,nh,rows);
    }

    // For all columns, for all NIDs, for all ROWS...
    private void accum_all2(Chunk chks[], Chunk wrks, int nh[], int[] rows) {
      final DHistogram hcs[][] = _hcs;
      // Local temp arrays, no atomic updates.
      int    bins[] = new int   [nbins];
      double sums[] = new double[nbins];
      double ssqs[] = new double[nbins];
      // For All Columns
      for( int c=0; c<_ncols; c++) { // for all columns
        Chunk chk = chks[c];
        // For All NIDs
        for( int n=0; n<hcs.length; n++ ) {
          final DRealHistogram rh = ((DRealHistogram)hcs[n][c]);
          if( rh==null ) continue; // Ignore untracked columns in this split
          final int lo = n==0 ? 0 : nh[n-1];
          final int hi = nh[n];
          float min = rh._min2;
          float max = rh._maxIn;
          // While most of the time we are limited to nbins, we allow more bins
          // in a few cases (top-level splits have few total bins across all
          // the (few) splits) so it's safe to bin more; also categoricals want
          // to split one bin-per-level no matter how many levels).
          if( rh._bins.length >= bins.length ) { // Grow bins if needed
            bins = new int   [rh._bins.length];
            sums = new double[rh._bins.length];
            ssqs = new double[rh._bins.length];
          }

          // Gather all the data for this set of rows, for 1 column and 1 split/NID
          // Gather min/max, sums and sum-squares.
          for( int xrow=lo; xrow<hi; xrow++ ) {
            int row = rows[xrow];
            float col_data = (float)chk.at0(row);
            if( col_data < min ) min = col_data;
            if( col_data > max ) max = col_data;
            int b = rh.bin(col_data); // Compute bin# via linear interpolation
            bins[b]++;                // Bump count in bin
            double resp = wrks.at0(row);
            sums[b] += resp;
            ssqs[b] += resp*resp;
          }

          // Add all the data into the Histogram (atomically add)
          rh.setMin(min);       // Track actual lower/upper bound per-bin
          rh.setMax(max);
          for( int b=0; b<rh._bins.length; b++ ) { // Bump counts in bins
            if( bins[b] != 0 ) { Utils.AtomicIntArray.add(rh._bins,b,bins[b]); bins[b]=0; }
            if( ssqs[b] != 0 ) { rh.incr1(b,sums[b],ssqs[b]); sums[b]=ssqs[b]=0; }
          }
        }
      }
    }
  }


  // --------------------------------------------------------------------------
  // Build an entire layer of all K trees
  protected DHistogram[][][] buildLayer(final Frame fr, final DTree ktrees[], final int leafs[], final DHistogram hcs[][][], boolean subset, boolean build_tree_one_node) {
    // Build K trees, one per class.

    // Build up the next-generation tree splits from the current histograms.
    // Nearly all leaves will split one more level.  This loop nest is
    //           O( #active_splits * #bins * #ncols )
    // but is NOT over all the data.
    H2OCountedCompleter sb1ts[] = new H2OCountedCompleter[_nclass];
    Vec vecs[] = fr.vecs();
    for( int k=0; k<_nclass; k++ ) {
      final DTree tree = ktrees[k]; // Tree for class K
      if( tree == null ) continue;
      // Build a frame with just a single tree (& work & nid) columns, so the
      // nested MRTask2 ScoreBuildHistogram in ScoreBuildOneTree does not try
      // to close other tree's Vecs when run in parallel.
      Frame fr2 = new Frame(Arrays.copyOf(fr._names,_ncols+1), Arrays.copyOf(vecs,_ncols+1));
      fr2.add(fr._names[_ncols+1+k],vecs[_ncols+1+k]);
      fr2.add(fr._names[_ncols+1+_nclass+k],vecs[_ncols+1+_nclass+k]);
      fr2.add(fr._names[_ncols+1+_nclass+_nclass+k],vecs[_ncols+1+_nclass+_nclass+k]);
      // Start building one of the K trees in parallel
      H2O.submitTask(sb1ts[k] = new ScoreBuildOneTree(k,tree,leafs,hcs,fr2, subset, build_tree_one_node));
    }
    // Block for all K trees to complete.
    boolean did_split=false;
    for( int k=0; k<_nclass; k++ ) {
      final DTree tree = ktrees[k]; // Tree for class K
      if( tree == null ) continue;
      sb1ts[k].join();
      if( ((ScoreBuildOneTree)sb1ts[k])._did_split ) did_split=true;
    }
    // The layer is done.
    return did_split ? hcs : null;
  }

  private class ScoreBuildOneTree extends H2OCountedCompleter {
    final int _k;               // The tree
    final DTree _tree;
    final int _leafs[/*nclass*/];
    final DHistogram _hcs[/*nclass*/][][];
    final Frame _fr2;
    final boolean _build_tree_one_node;
    final boolean _subset;      // True if working a subset of cols
    boolean _did_split;
    ScoreBuildOneTree( int k, DTree tree, int leafs[], DHistogram hcs[][][], Frame fr2, boolean subset, boolean build_tree_one_node ) {
      _k    = k;
      _tree = tree;
      _leafs= leafs;
      _hcs  = hcs;
      _fr2  = fr2;
      _subset = subset;
      _build_tree_one_node = build_tree_one_node;
    }
    @Override public void compute2() {
      // Fuse 2 conceptual passes into one:
      // Pass 1: Score a prior DHistogram, and make new Node assignments
      // to every row.  This involves pulling out the current assigned Node,
      // "scoring" the row against that Node's decision criteria, and assigning
      // the row to a new child Node (and giving it an improved prediction).
      // Pass 2: Build new summary DHistograms on the new child Nodes every row
      // got assigned into.  Collect counts, mean, variance, min, max per bin,
      // per column.
      new ScoreBuildHistogram(this,_k,_tree,_leafs[_k],_hcs[_k],_subset).dfork(0,_fr2,_build_tree_one_node);
    }
    @Override public void onCompletion(CountedCompleter caller) {
      ScoreBuildHistogram sbh = (ScoreBuildHistogram)caller;
      //System.out.println(sbh.profString());

      final int leafk = _leafs[_k];
      int tmax = _tree.len();   // Number of total splits in tree K
      for( int leaf=leafk; leaf<tmax; leaf++ ) { // Visit all the new splits (leaves)
        DTree.UndecidedNode udn = _tree.undecided(leaf);
        //System.out.println((_nclass==1?"Regression":("Class "+_fr2.vecs()[_ncols]._domain[_k]))+",\n  Undecided node:"+udn);
        // Replace the Undecided with the Split decision
        DTree.DecidedNode dn = makeDecided(udn,sbh._hcs[leaf-leafk]);
        //System.out.println("--> Decided node: " + dn +
        //                   "  > Split: " + dn._split + " L/R:" + dn._split.rowsLeft()+" + "+dn._split.rowsRight());
        if( dn._split.col() == -1 ) udn.do_not_split();
        else _did_split = true;
      }
      _leafs[_k]=tmax;          // Setup leafs for next tree level
      int new_leafs = _tree.len()-tmax;
      _hcs[_k] = new DHistogram[new_leafs][/*ncol*/];
      for( int nl = tmax; nl<_tree.len(); nl ++ )
        _hcs[_k][nl-tmax] = _tree.undecided(nl)._hs;
      if (new_leafs>0) _tree.depth++; // Next layer done but update tree depth only if new leaves are generated
    }
  }

  // Builder-specific decision node
  protected abstract DTree.DecidedNode makeDecided( DTree.UndecidedNode udn, DHistogram hs[] );

  // --------------------------------------------------------------------------
  // Read the 'tree' columns, do model-specific math and put the results in the
  // fs[] array, and return the sum.  Dividing any fs[] element by the sum
  // turns the results into a probability distribution.
  protected abstract float score1( Chunk chks[], float fs[/*nclass*/], int row );

  // Call builder specific score code and then correct probabilities
  // if it is necessary.
  private float score2(Chunk chks[], float fs[/*nclass*/], int row ) {
    float sum = score1(chks, fs, row);
    if (/*false &&*/ classification && _priorClassDist!=null && _modelClassDist!=null && !Float.isInfinite(sum)  && sum>0f) {
      Utils.div(fs, sum);
      ModelUtils.correctProbabilities(fs, _priorClassDist, _modelClassDist);
      sum = 1.0f;
    }
    return sum;
  }

  // Score the *tree* columns, and produce a confusion matrix
  public class Score extends MRTask2<Score> {
    /* @OUT */ long    _cm[/*actual*/][/*predicted*/]; // Confusion matrix
    /* @OUT */ double  _sum;                           // Sum-squared-error
    /* @OUT */ long    _snrows;                        // Count of voted-on rows
    /* @OUT */ long    _cms[/*threshold*/][/*actual*/][/*predicted*/]; // Compute CM per threshold for binary classifiers
    /* @IN */  boolean _oob;
    /* @IN */  boolean _validation;
    /* @IN */  int     _cmlen;
    /* @IN */  boolean _cavr; // true if validation response needs to be adapted to CM domain

    public double   sum()   { return _sum; }
    public long[][] cm ()   { return _cm;  }
    public long     nrows() { return _snrows; }
    public double   mse()   { return sum() / nrows(); }

    /**
     * Compute CM and MSE on either the training or testing dataset.
     *
     * It expect already adapted validation dataset which is adapted to a model
     * and contains a response which is adapted to confusion matrix domain. Uff :)
     *
     * @param model a model which is used to perform computation
     * @param fr    a model training frame
     * @param vadaptor an adaptor which helps to adapt model/validation response to confusion matrix domain.
     * @param oob   perform out-of-bag validation on training frame
     * @param build_tree_one_node
     * @return this score object
     */
    public Score doIt(Model model, Frame fr, Response2CMAdaptor vadaptor, boolean oob, boolean build_tree_one_node) {
      assert !oob || vadaptor.getValidation()==null : "Validation frame cannot be specified if oob validation is demanded!"; // oob => validation==null
      assert _nclass == 1 || vadaptor.getCMDomain() != null : "CM domain has to be configured from classification!";

      _cmlen = _nclass > 1 ? vadaptor.getCMDomain().length : 1;
      _oob = oob;
      // Validation frame adapted to a model
      Frame adaptedValidation = vadaptor.getValidation();
      // No validation frame is specified, so perform computation on training data
      if( adaptedValidation == null ) return doAll(fr, build_tree_one_node);
      _validation = true;
      _cavr       = false;
      // Validation: need to score the set, getting a probability distribution for each class
      // Frame has nclass vectors (nclass, or 1 for regression), for classification it
      Frame res = model.score(adaptedValidation, false); // For classification: predicted values (~ values in res[0]) are in interval 0..domain().length-1, for regression just single column.
      Frame adapValidation = new Frame(adaptedValidation); // adapted validation dataset
      // All columns including response of validation frame are already adapted to model
      if (_nclass>1) { // Only for Classification
        for( int i=0; i<_nclass; i++ ) // Distribution of response classes
          adapValidation.add("ClassDist"+i,res.vecs()[i+1]);
        if (vadaptor.needsAdaptation2CM()) {
          Vec ar = vadaptor.adaptModelResponse2CM(res.vecs()[0]); // perform transformation of model results to be consistent with expected confusion matrix domain
          adapValidation.add("Prediction", ar); // add as a prediction
          adapValidation.add("ActualValidationResponse", vadaptor.getAdaptedValidationResponse2CM());
          _cavr = true; // signal that we have two predictions vectors in the frame.
          res.add("__dummyx__", ar); // add the vector to clean up list
        } else
          adapValidation.add("Prediction",res.vecs()[0]); // Predicted values
      } else { // Regression
        adapValidation.add("Prediction",res.vecs()[0]);
      }
      // Compute a CM & MSE
      try {
        doAll(adapValidation, build_tree_one_node);
      } finally {
        // Perform clean-up: remove temporary result
        res.delete();
      }
      return this;
    }

    @Override public void map( Chunk chks[] ) {
      Chunk ys = chk_resp(chks); // Response
      Chunk ays = _cavr ? chks[_ncols+1+_nclass+1] : ys; // Remember adapted response
      _cm = new long[_cmlen][_cmlen];
      float fs[] = new float[_nclass+1]; // Array to hold prediction and distribution given by the model.
      // For binary classifier allocate cms for individual thresholds
      _cms = new long[ModelUtils.DEFAULT_THRESHOLDS.length][2][2];
      // Score all Rows
      for( int row=0; row<ys._len; row++ ) {
        if( ays.isNA0(row) ) continue; // Ignore missing response vars only if it was actual NA
        float sum;
        if( _validation ) {     // Passed in a class distribution from scoring
          for( int i=0; i<_nclass; i++ )
            fs[i+1] = (float)chk_tree(chks,i).at0(row); // Get the class distros
          if (_nclass > 1 ) sum = 1.0f;  // Sum of a distribution is 1.0 for classification
          else              sum = fs[1]; // Sum is the same as prediction for regression.
        } else {               // Passed in the model-specific columns
          sum = score2(chks,fs,row);
        }
        float err;  int yact=0; // actual response from dataset
        int yact_orig = 0; // actual response from dataset before potential scaling
        if (_oob && inBagRow(chks, row)) continue; // score only on out-of-bag rows
        if( _nclass > 1 ) {    // Classification
          // Compute error
          if( sum == 0 ) {                          // This tree does not predict this row *at all* ! In prediction we will make random decision, but here compute error based on number of classes
            yact = yact_orig = (int) ys.at80(row);  // OPS: Pick an actual prediction adapted to model values <0, nclass-1)
            err = 1.0f-1.0f/_nclass;                // Then take ycls=0, uniform predictive power
          } else {
            if (_cavr && ys.isNA0(row)) {           // Handle adapted validation response - actual response was adapted but does not contain NA - it is implicit misprediction,
              err = 1f;
            } else {                                // No adaptation of validation response
              yact = yact_orig = (int) ys.at80(row);// OPS: Pick an actual prediction adapted to model values <0, nclass-1)
              assert 0 <= yact && yact < _nclass : "weird ycls="+yact+", y="+ys.at0(row);
              err = Float.isInfinite(sum)
                ? (Float.isInfinite(fs[yact+1]) ? 0f : 1f)
                : 1.0f-fs[yact+1]/sum;              // Error: distance from predicting ycls as 1.0
            }
          }
          assert !Double.isNaN(err) : "fs[cls]="+fs[yact+1] + ", sum=" + sum;
          // Overwrite response by adapted value to provide correct CM
          if (_cavr) yact = (int) ays.at80(row);
        } else {                // Regression
          err = (float)ys.at0(row) - sum;
        }
        _sum += err*err;               // Squared error
        assert !Double.isNaN(_sum);
        // Pick highest prob for our prediction.
        if (_nclass > 1) { // fill CM only for classification
          if(_nclass == 2) { // Binomial classification -> compute AUC, draw ROC
            float snd = _validation ? fs[2] : (!Float.isInfinite(sum) ? fs[2] / sum : Float.isInfinite(fs[2]) ? 1 : 0); // for validation dataset sum is always 1
            for(int i = 0; i < ModelUtils.DEFAULT_THRESHOLDS.length; i++) {
              int p = snd >= ModelUtils.DEFAULT_THRESHOLDS[i] ? 1 : 0; // Compute prediction based on threshold
              _cms[i][yact_orig][p]++; // Increase matrix
            }
          }
          int ypred = _validation ? (int) chks[_ncols+1+_nclass].at80(row) : getPrediction(fs, row);
          _cm[yact][ypred]++;      // actual v. predicted
        }
        _snrows++;
      }
    }

    @Override public void reduce( Score t ) {
      _sum += t._sum;
      Utils.add(_cm,t._cm);
      _snrows += t._snrows;
      if (_cms!=null)
        for (int i = 0; i < _cms.length; i++) Utils.add(_cms[i], t._cms[i]);
    }

    public Score report( Sys tag, int ntree, DTree[] trees ) {
      assert !Double.isNaN(_sum);
      Log.info(tag,"============================================================== ");
      int lcnt=0;
      if( trees!=null ) for( DTree t : trees ) if( t != null ) lcnt += t._len;
      long err=_snrows;
      for( int c=0; c<_nclass; c++ ) err -= _cm[c][c];
      Log.info(tag,"Mean Squared Error is "+(_sum/_snrows)+", with "+ntree+"x"+_nclass+" trees (average of "+((float)lcnt/_nclass)+" nodes)");
      if( _nclass > 1 )
        Log.info(tag,"Total of "+err+" errors on "+_snrows+" rows, CM= "+Arrays.deepToString(_cm));
      else
        Log.info("Reported on "+_snrows+" rows.");
      return this;
    }
  }

  @Override public String speedDescription() { return "time/tree"; }
  @Override public long speedValue() {
    Value value = DKV.get(dest());
    DTree.TreeModel m = value != null ? (DTree.TreeModel) value.get() : null;
    long numTreesBuiltSoFar = m == null ? 0 : m.ntrees();
    long sv = (numTreesBuiltSoFar <= 0) ? 0 : (runTimeMs() / numTreesBuiltSoFar);
    return sv;
  }

  /** Returns a log tag for a particular model builder (e.g., DRF, GBM) */
  protected abstract water.util.Log.Tag.Sys logTag();
  /**
   * Builds model
   * @param initialModel initial model created by makeModel() method.
   * @param trainFr training dataset which can contain additional temporary vectors prepared by buildModel() method.
   * @param names names of columns in <code>trainFr</code> used for model training
   * @param domains domains of columns in <code>trainFr</code> used for model training
   * @param t_build timer to measure model building process
   * @return resulting model
   */
  protected abstract TM buildModel( TM initialModel, Frame trainFr, String names[], String domains[][], Timer t_build );
  /**
   * Initialize algorithm - e.g., allocate algorithm specific datastructure.
   *
   * @param initialModel
   */
  protected abstract void initAlgo( TM initialModel);

  /**
   * Initialize working frame.
   * @param initialModel  initial model
   * @param fr working frame which contains train data and additional columns prepared by this builder.
   */
  protected abstract void initWorkFrame( TM initialModel, Frame fr);

  protected abstract TM makeModel( Key outputKey, Key dataKey, Key testKey, int ntrees, String names[], String domains[][], String[] cmDomain, float[] priorClassDist, float[] classDist);
  protected abstract TM makeModel( TM model, double err, ConfusionMatrix cm, VarImp varimp, AUCData validAUC);
  protected abstract TM makeModel( TM model, DTree ktrees[], DTree.TreeModel.TreeStats tstats);
  protected abstract TM updateModel( TM model, TM checkpoint, boolean overwriteCheckpoint);

  protected AUCData makeAUC(ConfusionMatrix[] cms, float[] threshold) {
    assert _nclass == 2;
    return cms != null ? new AUC(cms, threshold, _cmDomain).data() : null;
  }

  protected boolean inBagRow(Chunk[] chks, int row) { return false; }
  protected final boolean isClassification() { return _nclass > 1; }

  static public final boolean isOOBRow(int nid)     { return nid <= OUT_OF_BAG; }
  static public final boolean isDecidedRow(int nid) { return nid == DECIDED_ROW; }
  static public final int     oob2Nid(int oobNid)   { return -oobNid + OUT_OF_BAG; }
  static public final int     nid2Oob(int nid)      { return -nid + OUT_OF_BAG; }

  // Helper to unify use of M-T RNG
  public static Random createRNG(long seed) {
    return new MersenneTwisterRNG(new int[] { (int)(seed>>32L),(int)seed });
  }

  // helper for debugging
  static protected void printGenerateTrees(DTree[] trees) {
    for( int k=0; k<trees.length; k++ )
      if( trees[k] != null )
        System.out.println(trees[k].root().toString2(new StringBuilder(),0));
  }

  protected final void debugPrintTreeColumns(Frame fr) {
    new MRTask2() {
      @Override public void map(Chunk[] cs) {
        for (int r=0; r<cs[0]._len; r++) {
          System.err.print("Row "+ r +": ");
          for (int i=0; i<_nclass; i++) {
            Chunk c = chk_tree(cs, i);
            System.err.print(c.at0(r));
            System.err.print(',');
          }
          if (supportsBagging()) {
            Chunk c = chk_oobt(cs);
            System.err.print(c.at80(r)>0 ? ":OUT" : ":IN");
          }
          System.err.println();
        }
      }
    }.doAll(fr);
  }
}
