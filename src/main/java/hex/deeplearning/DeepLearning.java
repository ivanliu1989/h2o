package hex.deeplearning;

import hex.*;
import water.*;
import water.util.*;
import static water.util.MRUtils.sampleFrame;
import static water.util.MRUtils.sampleFrameStratified;
import hex.FrameTask.DataInfo;
import water.api.*;
import water.fvec.Frame;
import water.fvec.RebalanceDataSet;
import water.fvec.Vec;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Random;

/**
 * Deep Learning Neural Net implementation based on MRTask2
 */
public class DeepLearning extends Job.ValidatedJob {
  static final int API_WEAVER = 1; // This file has auto-gen'd doc & json fields
  public static DocGen.FieldDoc[] DOC_FIELDS;
  public static final String DOC_GET = "Deep Learning";

  /**
   * A model key associated with a previously trained Deep Learning
   * model. This option allows users to build a new model as a
   * continuation of a previously generated model (e.g., by a grid search).
   */
  @API(help = "Model checkpoint to resume training with", filter= Default.class, json = true)
  public Key checkpoint;

  /**
   * If enabled, store the best model under the destination key of this model at the end of training.
   * Only applicable if training is not cancelled.
   */
  @API(help = "If enabled, override the final model with the best model found during training", filter= Default.class, json = true)
  public boolean override_with_best_model = true;

  /**
   * Unlock expert mode parameters than can affect model building speed,
   * predictive accuracy and scoring. Leaving expert mode parameters at default
   * values is fine for many problems, but best results on complex datasets are often
   * only attainable via expert mode options.
   */
  @API(help = "Enable expert mode (to access all options from GUI)", filter = Default.class, json = true)
  public boolean expert_mode = false;

  @API(help = "Auto-Encoder (Experimental)", filter= Default.class, json = true)
  public boolean autoencoder = false;

  @API(help="Use all factor levels of categorical variables. Otherwise, the first factor level is omitted (without loss of accuracy). Useful for variable importances and auto-enabled for autoencoder.",filter=Default.class, json=true, importance = ParamImportance.SECONDARY)
  public boolean use_all_factor_levels = true;

  /*Neural Net Topology*/
  /**
   * The activation function (non-linearity) to be used the neurons in the hidden layers.
   * Tanh: Hyperbolic tangent function (same as scaled and shifted sigmoid).
   * Rectifier: Chooses the maximum of (0, x) where x is the input value.
   * Maxout: Choose the maximum coordinate of the input vector.
   * With Dropout: Zero out a random user-given fraction of the
   *      incoming weights to each hidden layer during training, for each
   *      training row. This effectively trains exponentially many models at
   *      once, and can improve generalization.
   */
  @API(help = "Activation function", filter = Default.class, json = true, importance = ParamImportance.CRITICAL)
  public Activation activation = Activation.Rectifier;

  /**
   * The number and size of each hidden layer in the model.
   * For example, if a user specifies "100,200,100" a model with 3 hidden
   * layers will be produced, and the middle hidden layer will have 200
   * neurons.To specify a grid search, add parentheses around each
   * model's specification: "(100,100), (50,50,50), (20,20,20,20)".
   */
  @API(help = "Hidden layer sizes (e.g. 100,100). Grid search: (10,10), (20,20,20)", filter = Default.class, json = true, importance = ParamImportance.CRITICAL)
  public int[] hidden = new int[] { 200, 200 };

  /**
   * The number of passes over the training dataset to be carried out.
   * It is recommended to start with lower values for initial grid searches.
   * This value can be modified during checkpoint restarts and allows continuation
   * of selected models.
   */
  @API(help = "How many times the dataset should be iterated (streamed), can be fractional", filter = Default.class, dmin = 1e-3, json = true, importance = ParamImportance.CRITICAL)
  public double epochs = 10;

  /**
   * The number of training data rows to be processed per iteration. Note that
   * independent of this parameter, each row is used immediately to update the model
   * with (online) stochastic gradient descent. This parameter controls the
   * synchronization period between nodes in a distributed environment and the
   * frequency at which scoring and model cancellation can happen. For example, if
   * it is set to 10,000 on H2O running on 4 nodes, then each node will
   * process 2,500 rows per iteration, sampling randomly from their local data.
   * Then, model averaging between the nodes takes place, and scoring can happen
   * (dependent on scoring interval and duty factor). Special values are 0 for
   * one epoch per iteration, -1 for processing the maximum amount of data
   * per iteration (if **replicate training data** is enabled, N epochs
   * will be trained per iteration on N nodes, otherwise one epoch). Special value
   * of -2 turns on automatic mode (auto-tuning).
   */
  @API(help = "Number of training samples (globally) per MapReduce iteration. Special values are 0: one epoch, -1: all available data (e.g., replicated training data), -2: automatic", filter = Default.class, lmin = -2, json = true, importance = ParamImportance.SECONDARY)
  public long train_samples_per_iteration = -2;

//  @API(help = "Target ratio of communication overhead to computation. Only for multi-node operation and train_samples_per_iteration=-2 (auto-tuning)", filter = Default.class, dmin = 1e-3, dmax=0.999, json = true, importance = ParamImportance.SECONDARY)
  public double target_ratio_comm_to_comp = 0.02;

  /**
   * The random seed controls sampling and initialization. Reproducible
   * results are only expected with single-threaded operation (i.e.,
   * when running on one node, turning off load balancing and providing
   * a small dataset that fits in one chunk).  In general, the
   * multi-threaded asynchronous updates to the model parameters will
   * result in (intentional) race conditions and non-reproducible
   * results. Note that deterministic sampling and initialization might
   * still lead to some weak sense of determinism in the model.
   */
  @API(help = "Seed for random numbers (affects sampling) - Note: only reproducible when running single threaded", filter = Default.class, json = true)
  public long seed = new Random().nextLong();

  /*Adaptive Learning Rate*/
  /**
   * The implemented adaptive learning rate algorithm (ADADELTA) automatically
   * combines the benefits of learning rate annealing and momentum
   * training to avoid slow convergence. Specification of only two
   * parameters (rho and epsilon)  simplifies hyper parameter search.
   * In some cases, manually controlled (non-adaptive) learning rate and
   * momentum specifications can lead to better results, but require the
   * specification (and hyper parameter search) of up to 7 parameters.
   * If the model is built on a topology with many local minima or
   * long plateaus, it is possible for a constant learning rate to produce
   * sub-optimal results. Learning rate annealing allows digging deeper into
   * local minima, while rate decay allows specification of different
   * learning rates per layer.  When the gradient is being estimated in
   * a long valley in the optimization landscape, a large learning rate
   * can cause the gradient to oscillate and move in the wrong
   * direction. When the gradient is computed on a relatively flat
   * surface with small learning rates, the model can converge far
   * slower than necessary.
   */
  @API(help = "Adaptive learning rate (ADADELTA)", filter = Default.class, json = true, importance = ParamImportance.SECONDARY)
  public boolean adaptive_rate = true;

  /**
   * The first of two hyper parameters for adaptive learning rate (ADADELTA).
   * It is similar to momentum and relates to the memory to prior weight updates.
   * Typical values are between 0.9 and 0.999.
   * This parameter is only active if adaptive learning rate is enabled.
   */
  @API(help = "Adaptive learning rate time decay factor (similarity to prior updates)", filter = Default.class, dmin = 0.01, dmax = 1, json = true, importance = ParamImportance.SECONDARY)
  public double rho = 0.99;

  /**
   * The second of two hyper parameters for adaptive learning rate (ADADELTA).
   * It is similar to learning rate annealing during initial training
   * and momentum at later stages where it allows forward progress.
   * Typical values are between 1e-10 and 1e-4.
   * This parameter is only active if adaptive learning rate is enabled.
   */
  @API(help = "Adaptive learning rate smoothing factor (to avoid divisions by zero and allow progress)", filter = Default.class, dmin = 1e-15, dmax = 1, json = true, importance = ParamImportance.SECONDARY)
  public double epsilon = 1e-8;

  /*Learning Rate*/
  /**
   * When adaptive learning rate is disabled, the magnitude of the weight
   * updates are determined by the user specified learning rate
   * (potentially annealed), and are a function  of the difference
   * between the predicted value and the target value. That difference,
   * generally called delta, is only available at the output layer. To
   * correct the output at each hidden layer, back propagation is
   * used. Momentum modifies back propagation by allowing prior
   * iterations to influence the current update. Using the momentum
   * parameter can aid in avoiding local minima and the associated
   * instability. Too much momentum can lead to instabilities, that's
   * why the momentum is best ramped up slowly.
   * This parameter is only active if adaptive learning rate is disabled.
   */
  @API(help = "Learning rate (higher => less stable, lower => slower convergence)", filter = Default.class, dmin = 1e-10, dmax = 1, json = true, importance = ParamImportance.SECONDARY)
  public double rate = .005;

  /**
   * Learning rate annealing reduces the learning rate to "freeze" into
   * local minima in the optimization landscape.  The annealing rate is the
   * inverse of the number of training samples it takes to cut the learning rate in half
   * (e.g., 1e-6 means that it takes 1e6 training samples to halve the learning rate).
   * This parameter is only active if adaptive learning rate is disabled.
   */
  @API(help = "Learning rate annealing: rate / (1 + rate_annealing * samples)", filter = Default.class, dmin = 0, dmax = 1, json = true, importance = ParamImportance.SECONDARY)
  public double rate_annealing = 1e-6;

  /**
   * The learning rate decay parameter controls the change of learning rate across layers.
   * For example, assume the rate parameter is set to 0.01, and the rate_decay parameter is set to 0.5.
   * Then the learning rate for the weights connecting the input and first hidden layer will be 0.01,
   * the learning rate for the weights connecting the first and the second hidden layer will be 0.005,
   * and the learning rate for the weights connecting the second and third hidden layer will be 0.0025, etc.
   * This parameter is only active if adaptive learning rate is disabled.
   */
  @API(help = "Learning rate decay factor between layers (N-th layer: rate*alpha^(N-1))", filter = Default.class, dmin = 0, json = true, importance = ParamImportance.EXPERT)
  public double rate_decay = 1.0;

  /*Momentum*/
  /**
   * The momentum_start parameter controls the amount of momentum at the beginning of training.
   * This parameter is only active if adaptive learning rate is disabled.
   */
  @API(help = "Initial momentum at the beginning of training (try 0.5)", filter = Default.class, dmin = 0, dmax = 0.9999999999, json = true, importance = ParamImportance.SECONDARY)
  public double momentum_start = 0;

  /**
   * The momentum_ramp parameter controls the amount of learning for which momentum increases
   * (assuming momentum_stable is larger than momentum_start). The ramp is measured in the number
   * of training samples.
   * This parameter is only active if adaptive learning rate is disabled.
   */
  @API(help = "Number of training samples for which momentum increases", filter = Default.class, dmin = 1, json = true, importance = ParamImportance.SECONDARY)
  public double momentum_ramp = 1e6;

  /**
   * The momentum_stable parameter controls the final momentum value reached after momentum_ramp training samples.
   * The momentum used for training will remain the same for training beyond reaching that point.
   * This parameter is only active if adaptive learning rate is disabled.
   */
  @API(help = "Final momentum after the ramp is over (try 0.99)", filter = Default.class, dmin = 0, dmax = 0.9999999999, json = true, importance = ParamImportance.SECONDARY)
  public double momentum_stable = 0;

  /**
   * The Nesterov accelerated gradient descent method is a modification to
   * traditional gradient descent for convex functions. The method relies on
   * gradient information at various points to build a polynomial approximation that
   * minimizes the residuals in fewer iterations of the descent.
   * This parameter is only active if adaptive learning rate is disabled.
   */
  @API(help = "Use Nesterov accelerated gradient (recommended)", filter = Default.class, json = true, importance = ParamImportance.SECONDARY)
  public boolean nesterov_accelerated_gradient = true;

  /*Regularization*/
  /**
   * A fraction of the features for each training row to be omitted from training in order
   * to improve generalization (dimension sampling).
   */
  @API(help = "Input layer dropout ratio (can improve generalization, try 0.1 or 0.2)", filter = Default.class, dmin = 0, dmax = 1, json = true, importance = ParamImportance.SECONDARY)
  public double input_dropout_ratio = 0.0;

  /**
   * A fraction of the inputs for each hidden layer to be omitted from training in order
   * to improve generalization. Defaults to 0.5 for each hidden layer if omitted.
   */
  @API(help = "Hidden layer dropout ratios (can improve generalization), specify one value per hidden layer, defaults to 0.5", filter = Default.class, dmin = 0, dmax = 1, json = true, importance = ParamImportance.SECONDARY)
  public double[] hidden_dropout_ratios;

  /**
   * A regularization method that constrains the absolute value of the weights and
   * has the net effect of dropping some weights (setting them to zero) from a model
   * to reduce complexity and avoid overfitting.
   */
  @API(help = "L1 regularization (can add stability and improve generalization, causes many weights to become 0)", filter = Default.class, dmin = 0, dmax = 1, json = true, importance = ParamImportance.SECONDARY)
  public double l1 = 0.0;

  /**
   *  A regularization method that constrdains the sum of the squared
   * weights. This method introduces bias into parameter estimates, but
   * frequently produces substantial gains in modeling as estimate variance is
   * reduced.
   */
  @API(help = "L2 regularization (can add stability and improve generalization, causes many weights to be small", filter = Default.class, dmin = 0, dmax = 1, json = true, importance = ParamImportance.SECONDARY)
  public double l2 = 0.0;

  /**
   *  A maximum on the sum of the squared incoming weights into
   * any one neuron. This tuning parameter is especially useful for unbound
   * activation functions such as Maxout or Rectifier.
   */
  @API(help = "Constraint for squared sum of incoming weights per unit (e.g. for Rectifier)", filter = Default.class, dmin = 1e-10, json = true, importance = ParamImportance.EXPERT)
  public float max_w2 = Float.POSITIVE_INFINITY;

  /*Initialization*/
  /**
   * The distribution from which initial weights are to be drawn. The default
   * option is an optimized initialization that considers the size of the network.
   * The "uniform" option uses a uniform distribution with a mean of 0 and a given
   * interval. The "normal" option draws weights from the standard normal
   * distribution with a mean of 0 and given standard deviation.
   */
  @API(help = "Initial Weight Distribution", filter = Default.class, json = true, importance = ParamImportance.EXPERT)
  public InitialWeightDistribution initial_weight_distribution = InitialWeightDistribution.UniformAdaptive;

  /**
   * The scale of the distribution function for Uniform or Normal distributions.
   * For Uniform, the values are drawn uniformly from -initial_weight_scale...initial_weight_scale.
   * For Normal, the values are drawn from a Normal distribution with a standard deviation of initial_weight_scale.
   */
  @API(help = "Uniform: -value...value, Normal: stddev)", filter = Default.class, dmin = 0, json = true, importance = ParamImportance.EXPERT)
  public double initial_weight_scale = 1.0;

  /**
   * The loss (error) function to be minimized by the model.
   * Cross Entropy loss is used when the model output consists of independent
   * hypotheses, and the outputs can be interpreted as the probability that each
   * hypothesis is true. Cross entropy is the recommended loss function when the
   * target values are class labels, and especially for imbalanced data.
   * It strongly penalizes error in the prediction of the actual class label.
   * Mean Square loss is used when the model output are continuous real values, but can
   * be used for classification as well (where it emphasizes the error on all
   * output classes, not just for the actual class).
   */
  @API(help = "Loss function", filter = Default.class, json = true, importance = ParamImportance.EXPERT)
  public Loss loss = Loss.Automatic;

  /*Scoring*/
  /**
   * The minimum time (in seconds) to elapse between model scoring. The actual
   * interval is determined by the number of training samples per iteration and the scoring duty cycle.
   */
  @API(help = "Shortest time interval (in secs) between model scoring", filter = Default.class, dmin = 0, json = true, importance = ParamImportance.SECONDARY)
  public double score_interval = 5;

  /**
   * The number of training dataset points to be used for scoring. Will be
   * randomly sampled. Use 0 for selecting the entire training dataset.
   */
  @API(help = "Number of training set samples for scoring (0 for all)", filter = Default.class, lmin = 0, json = true, importance = ParamImportance.EXPERT)
  public long score_training_samples = 10000l;

  /**
   * The number of validation dataset points to be used for scoring. Can be
   * randomly sampled or stratified (if "balance classes" is set and "score
   * validation sampling" is set to stratify). Use 0 for selecting the entire
   * training dataset.
   */
  @API(help = "Number of validation set samples for scoring (0 for all)", filter = Default.class, lmin = 0, json = true, importance = ParamImportance.EXPERT)
  public long score_validation_samples = 0l;

  /**
   * Maximum fraction of wall clock time spent on model scoring on training and validation samples,
   * and on diagnostics such as computation of feature importances (i.e., not on training).
   */
  @API(help = "Maximum duty cycle fraction for scoring (lower: more training, higher: more scoring).", filter = Default.class, dmin = 0, dmax = 1, json = true, importance = ParamImportance.EXPERT)
  public double score_duty_cycle = 0.1;

  /**
   * The stopping criteria in terms of classification error (1-accuracy) on the
   * training data scoring dataset. When the error is at or below this threshold,
   * training stops.
   */
  @API(help = "Stopping criterion for classification error fraction on training data (-1 to disable)", filter = Default.class, dmin=-1, dmax=1, json = true, importance = ParamImportance.EXPERT)
  public double classification_stop = 0;

  /**
   * The stopping criteria in terms of regression error (MSE) on the training
   * data scoring dataset. When the error is at or below this threshold, training
   * stops.
   */
  @API(help = "Stopping criterion for regression error (MSE) on training data (-1 to disable)", filter = Default.class, dmin=-1, json = true, importance = ParamImportance.EXPERT)
  public double regression_stop = 1e-6;

  /**
   * Enable quiet mode for less output to standard output.
   */
  @API(help = "Enable quiet mode for less output to standard output", filter = Default.class, json = true)
  public boolean quiet_mode = false;

  /**
   * For classification models, the maximum size (in terms of classes) of the
   * confusion matrix for it to be printed. This option is meant to avoid printing
   * extremely large confusion matrices.
   */
  @API(help = "Max. size (number of classes) for confusion matrices to be shown", filter = Default.class, json = true)
  public int max_confusion_matrix_size = 20;

  /**
   * The maximum number (top K) of predictions to use for hit ratio computation (for multi-class only, 0 to disable)
   */
  @API(help = "Max. number (top K) of predictions to use for hit ratio computation (for multi-class only, 0 to disable)", filter = Default.class, lmin=0, json = true, importance = ParamImportance.EXPERT)
  public int max_hit_ratio_k = 10;

  /*Imbalanced Classes*/
  /**
   * For imbalanced data, balance training data class counts via
   * over/under-sampling. This can result in improved predictive accuracy.
   */
  @API(help = "Balance training data class counts via over/under-sampling (for imbalanced data)", filter = Default.class, json = true, importance = ParamImportance.EXPERT)
  public boolean balance_classes = false;

  /**
   * Desired over/under-sampling ratios per class (lexicographic order). Only when balance_classes is enabled. If not specified, they will be automatically computed to obtain class balance during training.
   */
  @API(help = "Desired over/under-sampling ratios per class (lexicographic order).", filter = Default.class, dmin = 0, json = true, importance = ParamImportance.SECONDARY)
  public float[] class_sampling_factors;

  /**
   * When classes are balanced, limit the resulting dataset size to the
   * specified multiple of the original dataset size.
   */
  @API(help = "Maximum relative size of the training data after balancing class counts (can be less than 1.0)", filter = Default.class, json = true, dmin=1e-3, importance = ParamImportance.EXPERT)
  public float max_after_balance_size = 5.0f;

  /**
   * Method used to sample the validation dataset for scoring, see Score Validation Samples above.
   */
  @API(help = "Method used to sample validation dataset for scoring", filter = Default.class, json = true, importance = ParamImportance.EXPERT)
  public ClassSamplingMethod score_validation_sampling = ClassSamplingMethod.Uniform;

  /*Misc*/
  /**
   * Gather diagnostics for hidden layers, such as mean and RMS values of learning
   * rate, momentum, weights and biases.
   */
  @API(help = "Enable diagnostics for hidden layers", filter = Default.class, json = true)
  public boolean diagnostics = true;

  /**
   * Whether to compute variable importances for input features.
   * The implemented method (by Gedeon) considers the weights connecting the
   * input features to the first two hidden layers.
   */
  @API(help = "Compute variable importances for input features (Gedeon method) - can be slow for large networks", filter = Default.class, json = true)
  public boolean variable_importances = false;

  /**
   * Enable fast mode (minor approximation in back-propagation), should not affect results significantly.
   */
  @API(help = "Enable fast mode (minor approximation in back-propagation)", filter = Default.class, json = true, importance = ParamImportance.EXPERT)
  public boolean fast_mode = true;

  /**
   * Ignore constant training columns (no information can be gained anyway).
   */
  @API(help = "Ignore constant training columns (no information can be gained anyway)", filter = Default.class, json = true, importance = ParamImportance.EXPERT)
  public boolean ignore_const_cols = true;

  /**
   * Increase training speed on small datasets by splitting it into many chunks
   * to allow utilization of all cores.
   */
  @API(help = "Force extra load balancing to increase training speed for small datasets (to keep all cores busy)", filter = Default.class, json = true)
  public boolean force_load_balance = true;

  /**
   * Replicate the entire training dataset onto every node for faster training on small datasets.
   */
  @API(help = "Replicate the entire training dataset onto every node for faster training on small datasets", filter = Default.class, json = true, importance = ParamImportance.EXPERT)
  public boolean replicate_training_data = true;

  /**
   * Run on a single node for fine-tuning of model parameters. Can be useful for
   * checkpoint resumes after training on multiple nodes for fast initial
   * convergence.
   */
  @API(help = "Run on a single node for fine-tuning of model parameters", filter = Default.class, json = true)
  public boolean single_node_mode = false;

  /**
   * Enable shuffling of training data (on each node). This option is
   * recommended if training data is replicated on N nodes, and the number of training samples per iteration
   * is close to N times the dataset size, where all nodes train will (almost) all
   * the data. It is automatically enabled if the number of training samples per iteration is set to -1 (or to N
   * times the dataset size or larger).
   */
  @API(help = "Enable shuffling of training data (recommended if training data is replicated and train_samples_per_iteration is close to #nodes x #rows)", filter = Default.class, json = true, importance = ParamImportance.EXPERT)
  public boolean shuffle_training_data = false;

//  @API(help = "Handling of missing values. Either Skip or MeanImputation.", filter= Default.class, json = true)
  public MissingValuesHandling missing_values_handling = MissingValuesHandling.MeanImputation;

  @API(help = "Sparse data handling (Experimental).", filter = Default.class, json = true, importance = ParamImportance.EXPERT)
  public boolean sparse = false;

  @API(help = "Use a column major weight matrix for input layer. Can speed up forward propagation, but might slow down backpropagation (Experimental).", filter = Default.class, json = true, importance = ParamImportance.EXPERT)
  public boolean col_major = false;

  @API(help = "Average activation for sparse auto-encoder (Experimental)", filter= Default.class, json = true)
  public double average_activation = 0;

  @API(help = "Sparsity regularization (Experimental)", filter= Default.class, json = true)
  public double sparsity_beta = 0;

  @API(help = "Max. number of categorical features, enforced via hashing (Experimental).", filter= Default.class, lmin = 1, json = true)
  public int max_categorical_features = Integer.MAX_VALUE;

  @API(help = "Force reproducibility on small data (will be slow - only uses 1 thread)", filter= Default.class, json = true)
  public boolean reproducible = false;

  public enum MissingValuesHandling {
    Skip, MeanImputation
  }

  public enum ClassSamplingMethod {
    Uniform, Stratified
  }

  public enum InitialWeightDistribution {
    UniformAdaptive, Uniform, Normal
  }

  /**
   * Activation functions
   */
  public enum Activation {
    Tanh, TanhWithDropout, Rectifier, RectifierWithDropout, Maxout, MaxoutWithDropout
  }

  /**
   * Loss functions
   * CrossEntropy is recommended
   */
  public enum Loss {
    Automatic, MeanSquare, CrossEntropy
  }

  // the following parameters can only be specified in expert mode
  transient final String [] expert_options = new String[] {
          "use_all_factor_levels",
          "loss",
          "max_w2",
          "score_training_samples",
          "score_validation_samples",
          "initial_weight_distribution",
          "initial_weight_scale",
          "diagnostics",
          "rate_decay",
          "score_duty_cycle",
          "variable_importances",
          "fast_mode",
          "score_validation_sampling",
          "ignore_const_cols",
          "force_load_balance",
          "replicate_training_data",
          "shuffle_training_data",
          "nesterov_accelerated_gradient",
          "classification_stop",
          "regression_stop",
          "quiet_mode",
          "max_confusion_matrix_size",
          "max_hit_ratio_k",
          "hidden_dropout_ratios",
          "single_node_mode",
          "sparse",
          "col_major",
          "autoencoder",
          "average_activation",
          "sparsity_beta",
          "max_categorical_features",
  };

  // the following parameters can be modified when restarting from a checkpoint
  transient final String [] cp_modifiable = new String[] {
          "expert_mode",
          "seed",
          "epochs",
          "score_interval",
          "train_samples_per_iteration",
          "target_ratio_comm_to_comp",
          "score_duty_cycle",
          "classification_stop",
          "regression_stop",
          "quiet_mode",
          "max_confusion_matrix_size",
          "max_hit_ratio_k",
          "diagnostics",
          "variable_importances",
          "force_load_balance",
          "replicate_training_data",
          "shuffle_training_data",
          "single_node_mode",
          "sparse",
          "col_major",
          // Allow modification of the regularization parameters after a checkpoint restart
          "l1",
          "l2",
          "max_w2",
  };

  /**
   * Helper to specify which arguments trigger a refresh on change
   * @param ver
   */
  @Override
  protected void registered(RequestServer.API_VERSION ver) {
    super.registered(ver);
    for (Argument arg : _arguments) {
      if ( arg._name.equals("activation") || arg._name.equals("initial_weight_distribution")
              || arg._name.equals("expert_mode") || arg._name.equals("adaptive_rate")
              || arg._name.equals("replicate_training_data")
              || arg._name.equals("balance_classes")
              || arg._name.equals("n_folds")
              || arg._name.equals("autoencoder")
              || arg._name.equals("checkpoint")) {
        arg.setRefreshOnChange();
      }
    }
  }

  /**
   * Helper to handle arguments based on existing input values
   * @param arg
   * @param inputArgs
   */
  @Override protected void queryArgumentValueSet(Argument arg, java.util.Properties inputArgs) {
    super.queryArgumentValueSet(arg, inputArgs);

    if (!arg._name.equals("checkpoint") && !Utils.contains(cp_modifiable, arg._name)) {
      if (checkpoint != null) {
        arg.disable("Taken from model checkpoint.");
        final DeepLearningModel cp_model = UKV.get(checkpoint);
        if (cp_model == null) {
          throw new IllegalArgumentException("Checkpointed model was not found.");
        }
        if (cp_model.model_info().unstable()) {
          throw new IllegalArgumentException("Checkpointed model was unstable. Not restarting.");
        }
        return;
      }
    }
    if(arg._name.equals("initial_weight_scale") &&
            (initial_weight_distribution == InitialWeightDistribution.UniformAdaptive)
            ) {
      arg.disable("Using sqrt(6 / (# units + # units of previous layer)) for Uniform distribution.", inputArgs);
    }
    if (classification) {
      if(arg._name.equals("regression_stop")) {
        arg.disable("Only for regression.", inputArgs);
      }
      if((arg._name.equals("max_after_balance_size") || arg._name.equals("class_sampling_factors")) && !balance_classes) {
        arg.disable("Requires balance_classes.", inputArgs);
      }
    }
    else {
      if(arg._name.equals("classification_stop")
              || arg._name.equals("max_confusion_matrix_size")
              || arg._name.equals("max_hit_ratio_k")
              || arg._name.equals("max_after_balance_size")
              || arg._name.equals("balance_classes")
              || arg._name.equals("class_sampling_factors")
              ) {
        arg.disable("Only for classification.", inputArgs);
      }
      if (validation != null && arg._name.equals("score_validation_sampling")) {
        score_validation_sampling = ClassSamplingMethod.Uniform;
        arg.disable("Using uniform sampling for validation scoring dataset.", inputArgs);
      }
    }
    if ((arg._name.equals("score_validation_samples") || arg._name.equals("score_validation_sampling")) && validation == null) {
      arg.disable("Requires a validation data set.", inputArgs);
    }
    if (Utils.contains(expert_options, arg._name) && !expert_mode) {
      arg.disable("Only in expert mode.", inputArgs);
    }
    if (!adaptive_rate) {
      if (arg._name.equals("rho") || arg._name.equals("epsilon")) {
        arg.disable("Only for adaptive learning rate.", inputArgs);
        rho = 0;
        epsilon = 0;
      }
    } else {
      if (arg._name.equals("rate") || arg._name.equals("rate_annealing") || arg._name.equals("rate_decay") || arg._name.equals("nesterov_accelerated_gradient")
              || arg._name.equals("momentum_start") || arg._name.equals("momentum_ramp") || arg._name.equals("momentum_stable") ) {
        arg.disable("Only for non-adaptive learning rate.", inputArgs);
        momentum_start = 0;
        momentum_stable = 0;
      }
    }
    if (arg._name.equals("hidden_dropout_ratios")) {
      if (activation != Activation.TanhWithDropout && activation != Activation.MaxoutWithDropout && activation != Activation.RectifierWithDropout) {
        arg.disable("Only for activation functions with dropout.", inputArgs);
      }
    }
    if (arg._name.equals("replicate_training_data") && (H2O.CLOUD.size() == 1)) {
      arg.disable("Only for multi-node operation.");
      replicate_training_data = false;
    }
    if (arg._name.equals("single_node_mode") && (H2O.CLOUD.size() == 1 || !replicate_training_data)) {
      arg.disable("Only for multi-node operation with replication.");
      single_node_mode = false;
    }
    if (arg._name.equals("use_all_factor_levels") && autoencoder ) {
      arg.disable("Automatically enabled for auto-encoders.");
      use_all_factor_levels = true;
    }
    if(arg._name.equals("override_with_best_model") && n_folds != 0) {
      arg.disable("Only without n-fold cross-validation.", inputArgs);
      override_with_best_model = false;
    }
    if(arg._name.equals("average_activation") && !autoencoder) {
      arg.disable("Only for autoencoder.", inputArgs);
    }
    if(arg._name.equals("sparsity_beta") && !autoencoder) {
      arg.disable("Only for autoencoder.", inputArgs);
    }
  }

  /** Print model parameters as JSON */
  @Override public boolean toHTML(StringBuilder sb) {
    try {
      return makeJsonBox(sb);
    } catch (Throwable t) {
      return false;
    }
  }

  /**
   * Return a query link to this page
   * @param k Model Key
   * @param content Link text
   * @return HTML Link
   */
  public static String link(Key k, String content) {
    return link(k, content, null, null, null);
  }

  /**
   * Return a query link to this page
   * @param k Model Key
   * @param content Link text
   * @param cp Key to checkpoint to continue training with (optional)
   * @param response Response
   * @param val Validation data set key
   * @return HTML Link
   */
  public static String link(Key k, String content, Key cp, String response, Key val) {
    DeepLearning req = new DeepLearning();
    RString rs = new RString("<a href='" + req.href() + ".query?source=%$key" +
            (cp == null ? "" : "&checkpoint=%$cp") +
            (response == null ? "" : "&response=%$resp") +
            (val == null ? "" : "&validation=%$valkey") +
            "'>%content</a>");
    rs.replace("key", k.toString());
    rs.replace("content", content);
    if (cp != null) rs.replace("cp", cp.toString());
    if (response != null) rs.replace("resp", response);
    if (val != null) rs.replace("valkey", val);
    return rs.toString();
  }

  /**
   * Report the relative progress of building a Deep Learning model (measured by how many epochs are done)
   * @return floating point number between 0 and 1
   */
  @Override public float progress(){
    if(UKV.get(dest()) == null)return 0;
    DeepLearningModel m = UKV.get(dest());
    if (m != null && m.model_info()!=null ) {
      final float p = (float) Math.min(1, (m.epoch_counter / m.model_info().get_params().epochs));
      return cv_progress(p);
    }
    return 0;
  }

  @Override
  protected final void execImpl() {
    try {
      buildModel();
      if (n_folds > 0) CrossValUtils.crossValidate(this);
    } finally {
      delete();
      state = UKV.<Job>get(self()).state;
      new TAtomic<DeepLearningModel>() {
        @Override
        public DeepLearningModel atomic(DeepLearningModel m) {
          if (m != null) m.get_params().state = state;
          return m;
        }
      }.invoke(dest());
    }
  }

  /**
   * Train a Deep Learning model, assumes that all members are populated
   * If checkpoint == null, then start training a new model, otherwise continue from a checkpoint
   */
  private void buildModel() {
    DeepLearningModel cp = null;
    if (checkpoint == null) {
      cp = initModel();
      cp.start_training(null);
    } else {
      final DeepLearningModel previous = UKV.get(checkpoint);
      if (previous == null) throw new IllegalArgumentException("Checkpoint not found.");
      Log.info("Resuming from checkpoint.");
      if (n_folds != 0) {
        throw new UnsupportedOperationException("n_folds must be 0: Cross-validation is not supported during checkpoint restarts.");
      }
      else {
        ((ValidatedJob)previous.job()).xval_models = null; //remove existing cross-validation keys after checkpoint restart
      }
      if (source == null || (previous.model_info().get_params().source != null && !Arrays.equals(source._key._kb, previous.model_info().get_params().source._key._kb))) {
        throw new IllegalArgumentException("source must be the same as for the checkpointed model.");
      }
      autoencoder = previous.model_info().get_params().autoencoder;
      if (!autoencoder && (response == null || !source.names()[source.find(response)].equals(previous.responseName()))) {
        throw new IllegalArgumentException("response must be the same as for the checkpointed model.");
      }
//      if (!autoencoder && (response == null || !Arrays.equals(response._key._kb, previous.model_info().get_params().response._key._kb))) {
//        throw new IllegalArgumentException("response must be the same as for the checkpointed model.");
//      }
      if (Utils.difference(ignored_cols, previous.model_info().get_params().ignored_cols).length != 0
              || Utils.difference(previous.model_info().get_params().ignored_cols, ignored_cols).length != 0) {
        ignored_cols = previous.model_info().get_params().ignored_cols;
        Log.warn("Automatically re-using ignored_cols from the checkpointed model.");
      }
      if ((validation == null) == (previous._validationKey != null)
              || (validation != null && validation._key != null && previous._validationKey != null
              && !Arrays.equals(validation._key._kb, previous._validationKey._kb))) {
        throw new IllegalArgumentException("validation must be the same as for the checkpointed model.");
      }
      if (classification != previous.model_info().get_params().classification) {
        Log.warn("Automatically switching to " + ((classification=!classification) ? "classification" : "regression") + " (same as the checkpointed model).");
      }
      epochs += previous.epoch_counter; //add new epochs to existing model
      Log.info("Adding " + String.format("%.3f", previous.epoch_counter) + " epochs from the checkpointed model.");
      try {
        final DataInfo dataInfo = prepareDataInfo();
        cp = new DeepLearningModel(previous, destination_key, job_key, dataInfo);
        cp.write_lock(self());
        cp.start_training(previous);
        assert(state==JobState.RUNNING);
        final DeepLearning A = cp.model_info().get_params();
        Object B = this;
        for (Field fA : A.getClass().getDeclaredFields()) {
          if (Utils.contains(cp_modifiable, fA.getName())) {
            if (!expert_mode && Utils.contains(expert_options, fA.getName())) continue;
            for (Field fB : B.getClass().getDeclaredFields()) {
              if (fA.equals(fB)) {
                try {
                  if (fB.get(B) == null || fA.get(A) == null || !fA.get(A).toString().equals(fB.get(B).toString())) { // if either of the two parameters is null, skip the toString()
                    if (fA.get(A) == null && fB.get(B) == null) continue; //if both parameters are null, we don't need to do anything
                    Log.info("Applying user-requested modification of '" + fA.getName() + "': " + fA.get(A) + " -> " + fB.get(B));
                    fA.set(A, fB.get(B));
                  }
                } catch (IllegalAccessException e) {
                  e.printStackTrace();
                }
              }
            }
          }
        }
        if (A.n_folds != 0) {
          Log.warn("Disabling cross-validation: Not supported when resuming training from a checkpoint.");
          A.n_folds = 0;
        }
        cp.update(self());
      } finally {
        if (cp != null) cp.unlock(self());
      }
    }
    trainModel(cp);
    cp.stop_training();
  }

  /**
   * Redirect to the model page for that model that is trained by this job
   * @return Response
   */
  @Override protected Response redirect() {
    return DeepLearningProgressPage.redirect(this, self(), dest());
  }

  private boolean _fakejob;
  //Sanity check for Deep Learning job parameters
  private void checkParams() {
    if (source.numCols() <= 1)
      throw new IllegalArgumentException("Training data must have at least 2 features (incl. response).");

    if (hidden == null) throw new IllegalArgumentException("There must be at least one hidden layer.");

    for (int i=0;i<hidden.length;++i) {
      if (hidden[i]==0)
        throw new IllegalArgumentException("Hidden layer size must be >0.");
    }

    //Auto-fill defaults
    if (hidden_dropout_ratios == null) {
      if (activation == Activation.TanhWithDropout || activation == Activation.MaxoutWithDropout || activation == Activation.RectifierWithDropout) {
        hidden_dropout_ratios = new double[hidden.length];
        if (!quiet_mode) Log.info("Automatically setting all hidden dropout ratios to 0.5.");
        Arrays.fill(hidden_dropout_ratios, 0.5);
      }
    }
    else if (hidden_dropout_ratios.length != hidden.length) throw new IllegalArgumentException("Must have " + hidden.length + " hidden layer dropout ratios.");
    else if (activation != Activation.TanhWithDropout && activation != Activation.MaxoutWithDropout && activation != Activation.RectifierWithDropout) {
      if (!quiet_mode) Log.info("Ignoring hidden_dropout_ratios because a non-Dropout activation function was specified.");
    }
    if (input_dropout_ratio < 0 || input_dropout_ratio >= 1) {
      throw new IllegalArgumentException("Input dropout must be in [0,1).");
    }
    if (class_sampling_factors != null || !balance_classes) {
      if (!quiet_mode) Log.info("Ignoring class_sampling_factors since balance_classes is not enabled.");
    }

    if (!quiet_mode) {
      if (adaptive_rate) {
        Log.info("Using automatic learning rate.  Ignoring the following input parameters:");
        Log.info("  rate, rate_decay, rate_annealing, momentum_start, momentum_ramp, momentum_stable, nesterov_accelerated_gradient.");
      } else {
        Log.info("Using manual learning rate.  Ignoring the following input parameters:");
        Log.info("  rho, epsilon.");
      }

      if (initial_weight_distribution == InitialWeightDistribution.UniformAdaptive) {
        Log.info("Ignoring initial_weight_scale for UniformAdaptive weight distribution.");
      }
      if (n_folds != 0) {
        if (override_with_best_model) {
          Log.info("Automatically setting override_with_best_model to false, since the final model is the only scored model with n-fold cross-validation.");
          override_with_best_model = false;
        }
      }
    }

    if(loss == Loss.Automatic) {
      if (!classification) {
        if (!quiet_mode) Log.info("Automatically setting loss to MeanSquare for regression.");
        loss = Loss.MeanSquare;
      }
      else if (autoencoder) {
        if (!quiet_mode) Log.info("Automatically setting loss to MeanSquare for auto-encoder.");
        loss = Loss.MeanSquare;
      }
      else {
        if (!quiet_mode) Log.info("Automatically setting loss to Cross-Entropy for classification.");
        loss = Loss.CrossEntropy;
      }
    }

    if(autoencoder && sparsity_beta > 0) {
      if (activation == Activation.Tanh || activation == Activation.TanhWithDropout) {
        if (average_activation >= 1 || average_activation <= -1)
          throw new IllegalArgumentException("Tanh average activation must be in (-1,1).");
      }
      else if (activation == Activation.Rectifier || activation == Activation.RectifierWithDropout) {
        if (average_activation <= 0)
          throw new IllegalArgumentException("Rectifier average activation must be positive.");
      }
    }

    if (!classification && loss == Loss.CrossEntropy) throw new IllegalArgumentException("Cannot use CrossEntropy loss function for regression.");
    if (autoencoder && loss != Loss.MeanSquare) throw new IllegalArgumentException("Must use MeanSquare loss function for auto-encoder.");
    if (autoencoder && classification) { classification = false; Log.info("Using regression mode for auto-encoder.");}

    // reason for the error message below is that validation might not have the same horizontalized features as the training data (or different order)
    if (autoencoder && validation != null) throw new UnsupportedOperationException("Cannot specify a validation dataset for auto-encoder.");
    if (autoencoder && activation == Activation.Maxout) throw new UnsupportedOperationException("Maxout activation is not supported for auto-encoder.");
    if (max_categorical_features < 1) throw new IllegalArgumentException("max_categorical_features must be at least " + 1);

    // make default job_key and destination_key in case they are missing
    if (dest() == null) {
      destination_key = Key.make();
    }
    if (self() == null) {
      job_key = Key.make();
    }
    if (UKV.get(self()) == null) {
      start_time = System.currentTimeMillis();
      state      = JobState.RUNNING;
      UKV.put(self(), this);
      _fakejob = true;
    }
    if (!sparse && col_major) {
      if (!quiet_mode) throw new IllegalArgumentException("Cannot use column major storage for non-sparse data handling.");
    }
    if (reproducible) {
      if (!quiet_mode)
        Log.info("Automatically enabling force_load_balancing, disabling single_node_mode and replicate_training_data\nand setting train_samples_per_iteration to -1 to enforce reproducibility.");
      force_load_balance = true;
      single_node_mode = false;
      train_samples_per_iteration = -1;
      replicate_training_data = false; //there's no benefit from having multiple nodes compute the exact same thing, and then average it back to the same
//      replicate_training_data = true; //doesn't hurt, but does replicated identical work
    }
  }

  /**
   * Helper to create a DataInfo object from the source and response
   * @return DataInfo object
   */
  private DataInfo prepareDataInfo() {
    final boolean del_enum_resp = classification && !response.isEnum();
    final Frame train = FrameTask.DataInfo.prepareFrame(source, autoencoder ? null : response, ignored_cols, classification, ignore_const_cols, true /*drop >20% NA cols*/);
    final DataInfo dinfo = new FrameTask.DataInfo(train, autoencoder ? 0 : 1, autoencoder || use_all_factor_levels, //use all FactorLevels for auto-encoder
            autoencoder ? DataInfo.TransformType.NORMALIZE : DataInfo.TransformType.STANDARDIZE, //transform predictors
            classification ? DataInfo.TransformType.NONE : DataInfo.TransformType.STANDARDIZE);  //transform response
    if (!autoencoder) {
      final Vec resp = dinfo._adaptedFrame.lastVec(); //convention from DataInfo: response is the last Vec
      assert (!classification ^ resp.isEnum()) : "Must have enum response for classification!"; //either regression or enum response
      if (del_enum_resp) ltrash(resp);
    }
    return dinfo;
  }

  /**
   * Create an initial Deep Learning model, typically to be trained by trainModel(model)
   * @return Randomly initialized model
   */
  public final DeepLearningModel initModel() {
    try {
      lock_data();
      checkParams();
      final DataInfo dinfo = prepareDataInfo();
      final Vec resp = dinfo._adaptedFrame.lastVec(); //convention from DataInfo: response is the last Vec
      float[] priorDist = classification ? new MRUtils.ClassDist(resp).doAll(resp).rel_dist() : null;
      final DeepLearningModel model = new DeepLearningModel(dest(), self(), source._key, dinfo, (DeepLearning)this.clone(), priorDist);
      model.model_info().initializeMembers();
      return model;
    }
    finally {
      unlock_data();
    }
  }


  /**
   * Helper to update a Frame and adding it to the local trash at the same time
   * @param target Frame referece, to be overwritten
   * @param src Newly made frame, to be deleted via local trash
   * @return src
   */
  Frame updateFrame(Frame target, Frame src) {
    if (src != target) ltrash(src);
    return src;
  }

  /**
   * Train a Deep Learning neural net model
   * @param model Input model (e.g., from initModel(), or from a previous training run)
   * @return Trained model
   */
  public final DeepLearningModel trainModel(DeepLearningModel model) {
    Frame validScoreFrame = null;
    Frame train, trainScoreFrame;
    try {
      lock_data();
      if (checkpoint == null && !quiet_mode) logStart(); //if checkpoint is given, some Job's params might be uninitialized (but the restarted model's parameters are correct)
      if (model == null) {
        model = UKV.get(dest());
      }
      model.write_lock(self());
      final DeepLearning mp = model.model_info().get_params(); //use the model's parameters for everything below - NOT the job's parameters (can be different after checkpoint restart)

      prepareValidationWithModel(model);
      final long model_size = model.model_info().size();
      if (!quiet_mode) Log.info("Number of model parameters (weights/biases): " + String.format("%,d", model_size));
      train = model.model_info().data_info()._adaptedFrame;
      if (mp.force_load_balance) train = updateFrame(train, reBalance(train, mp.replicate_training_data));
      if (mp.classification && mp.balance_classes) {
        float[] trainSamplingFactors = new float[train.lastVec().domain().length]; //leave initialized to 0 -> will be filled up below
        if (class_sampling_factors != null) {
          if (class_sampling_factors.length != train.lastVec().domain().length)
            throw new IllegalArgumentException("class_sampling_factors must have " + train.lastVec().domain().length + " elements");
          trainSamplingFactors = class_sampling_factors.clone(); //clone: don't modify the original
        }
        train = updateFrame(train, sampleFrameStratified(
                train, train.lastVec(), trainSamplingFactors, (long)(mp.max_after_balance_size*train.numRows()), mp.seed, true, false));
        model.setModelClassDistribution(new MRUtils.ClassDist(train.lastVec()).doAll(train.lastVec()).rel_dist());
      }
      model.training_rows = train.numRows();
      trainScoreFrame = updateFrame(train, sampleFrame(train, mp.score_training_samples, mp.seed)); //training scoring dataset is always sampled uniformly from the training dataset

      if (!quiet_mode) Log.info("Number of chunks of the training data: " + train.anyVec().nChunks());
      if (validation != null) {
        model.validation_rows = validation.numRows();
        Frame adaptedValid = getValidation();
        if (getValidAdaptor().needsAdaptation2CM()) {
          adaptedValid.add(getValidAdaptor().adaptedValidationResponse(_responseName), getValidAdaptor().getAdaptedValidationResponse2CM());
        }
        // validation scoring dataset can be sampled in multiple ways from the given validation dataset
        if (mp.classification && mp.balance_classes && mp.score_validation_sampling == ClassSamplingMethod.Stratified) {
          validScoreFrame = updateFrame(adaptedValid, sampleFrameStratified(adaptedValid, adaptedValid.lastVec(), null,
                  mp.score_validation_samples > 0 ? mp.score_validation_samples : adaptedValid.numRows(), mp.seed+1, false /* no oversampling */, false));
        } else {
          validScoreFrame = updateFrame(adaptedValid, sampleFrame(adaptedValid, mp.score_validation_samples, mp.seed+1));
        }
        if (mp.force_load_balance) validScoreFrame = updateFrame(validScoreFrame, reBalance(validScoreFrame, false /*always split up globally since scoring should be distributed*/));
        if (!quiet_mode) Log.info("Number of chunks of the validation data: " + validScoreFrame.anyVec().nChunks());
      }

      // Set train_samples_per_iteration size (cannot be done earlier since this depends on whether stratified sampling is done)
      model.actual_train_samples_per_iteration = computeTrainSamplesPerIteration(mp, train.numRows(), model);
      // Determine whether shuffling is enforced
      if(mp.replicate_training_data && (model.actual_train_samples_per_iteration == train.numRows()*(mp.single_node_mode?1:H2O.CLOUD.size())) && !mp.shuffle_training_data && H2O.CLOUD.size() > 1 && !mp.reproducible) {
        Log.warn("Enabling training data shuffling, because all nodes train on the full dataset (replicated training data).");
        mp.shuffle_training_data = true;
      }

      model._timeLastScoreEnter = System.currentTimeMillis(); //to keep track of time per iteration, must be called before first call to doScoring


      if (!mp.quiet_mode) Log.info("Initial model:\n" + model.model_info());
      if (autoencoder) model.doScoring(train, trainScoreFrame, validScoreFrame, self(), getValidAdaptor()); //get the null model reconstruction error
      // put the initial version of the model into DKV
      model.update(self());
      Log.info("Starting to train the Deep Learning model.");

      //main loop
      do model.set_model_info(H2O.CLOUD.size() > 1 && mp.replicate_training_data ? ( mp.single_node_mode ?
              new DeepLearningTask2(train, model.model_info(), rowFraction(train, mp, model)).invoke(Key.make()).model_info() : //replicated data + single node mode
              new DeepLearningTask2(train, model.model_info(), rowFraction(train, mp, model)).invokeOnAllNodes().model_info() ) : //replicated data + multi-node mode
              new DeepLearningTask(model.model_info(), rowFraction(train, mp, model)).doAll(train).model_info()); //distributed data (always in multi-node mode)
      while (model.doScoring(train, trainScoreFrame, validScoreFrame, self(), getValidAdaptor()));

      // replace the model with the best model so far (if it's better)
      if (!isCancelledOrCrashed() && override_with_best_model && model.actual_best_model_key != null && n_folds == 0) {
        DeepLearningModel best_model = UKV.get(model.actual_best_model_key);
        if (best_model != null && best_model.error() < model.error() && Arrays.equals(best_model.model_info().units, model.model_info().units)) {
          Log.info("Setting the model to be the best model so far (based on scoring history).");
          DeepLearningModel.DeepLearningModelInfo mi = best_model.model_info().deep_clone();
          // Don't cheat - count full amount of training samples, since that's the amount of training it took to train (without finding anything better)
          mi.set_processed_global(model.model_info().get_processed_global());
          mi.set_processed_local(model.model_info().get_processed_local());
          model.set_model_info(mi);
          model.update(self());
          model.doScoring(train, trainScoreFrame, validScoreFrame, self(), getValidAdaptor());
          assert(best_model.error() == model.error());
        }
      }

      Log.info(model);
      Log.info("Finished training the Deep Learning model.");
      return model;
    }
    catch(JobCancelledException ex) {
      model = UKV.get(dest());
      state = JobState.CANCELLED; //for JSON REST response
      model.get_params().state = state; //for parameter JSON on the HTML page
      Log.info("Deep Learning model building was cancelled.");
      return model;
    }
    finally {
      if (model != null) model.unlock(self());
      unlock_data();
    }
  }

  /**
   * Lock the input datasets against deletes
   */
  private void lock_data() {
    source.read_lock(self());
    if( validation != null && source._key != null && validation._key !=null && !source._key.equals(validation._key) )
      validation.read_lock(self());
  }

  /**
   * Release the lock for the input datasets
   */
  private void unlock_data() {
    source.unlock(self());
    if( validation != null && source._key != null && validation._key != null && !source._key.equals(validation._key) )
      validation.unlock(self());
  }

  /**
   * Delete job related keys
   */
  public void delete() {
    cleanup();
    if (_fakejob) UKV.remove(job_key);
    remove();
  }

  /**
   * Rebalance a frame for load balancing
   * @param fr Input frame
   * @param local whether to only create enough chunks to max out all cores on one node only
   * @return Frame that has potentially more chunks
   */
  private Frame reBalance(final Frame fr, boolean local) {
    int chunks = (int)Math.min( 4 * H2O.NUMCPUS * (local ? 1 : H2O.CLOUD.size()), fr.numRows());
    if (fr.anyVec().nChunks() > chunks && !reproducible) {
      Log.info("Dataset already contains " + fr.anyVec().nChunks() + " chunks. No need to rebalance.");
      return fr;
    } else if (reproducible) {
      Log.warn("Reproducibility enforced - using only 1 thread - can be slow.");
      chunks = 1;
    }
    if (!quiet_mode) Log.info("ReBalancing dataset into (at least) " + chunks + " chunks.");
//      return MRUtils.shuffleAndBalance(fr, chunks, seed, local, shuffle_training_data);
    String snewKey = fr._key != null ? (fr._key.toString() + ".balanced") : Key.rand();
    Key newKey = Key.makeSystem(snewKey);
    RebalanceDataSet rb = new RebalanceDataSet(fr, newKey, chunks);
    H2O.submitTask(rb);
    rb.join();
    return UKV.get(newKey);
  }

  /**
   * Compute the actual train_samples_per_iteration size from the user-given parameter
   * @param mp Model parameter (DeepLearning object)
   * @param numRows number of training rows
   * @param model DL Model
   * @return The total number of training rows to be processed per iteration (summed over on all nodes)
   */
  private static long computeTrainSamplesPerIteration(final DeepLearning mp, final long numRows, DeepLearningModel model) {
    long tspi = mp.train_samples_per_iteration;
    assert(tspi == 0 || tspi == -1 || tspi == -2 || tspi >= 1);
    if (tspi == 0 || (!mp.replicate_training_data && tspi == -1) ) {
      tspi = numRows;
      if (!mp.quiet_mode) Log.info("Setting train_samples_per_iteration (" + mp.train_samples_per_iteration + ") to one epoch: #rows (" + tspi + ").");
    }
    else if (tspi == -1) {
      tspi = (mp.single_node_mode ? 1 : H2O.CLOUD.size()) * numRows;
      if (!mp.quiet_mode) Log.info("Setting train_samples_per_iteration (" + mp.train_samples_per_iteration + ") to #nodes x #rows (" + tspi + ").");
    } else if (tspi == -2) {
      // automatic tuning based on CPU speed, network speed and model size

      // measure cpu speed
      double total_gflops = 0;
      for (H2ONode h2o : H2O.CLOUD._memary) {
        HeartBeat hb = h2o._heartbeat;
        total_gflops += hb._gflops;
      }
      if (mp.single_node_mode) total_gflops /= H2O.CLOUD.size();
      if (total_gflops == 0) {
        total_gflops = Linpack.run(H2O.SELF._heartbeat._cpus_allowed) * (mp.single_node_mode ? 1 : H2O.CLOUD.size());
      }

      final long model_size = model.model_info().size();
      int[] msg_sizes = new int[]{ (int)(model_size*4) == (model_size*4) ? (int)(model_size*4) : Integer.MAX_VALUE };
      double[] microseconds_collective = new double[msg_sizes.length];
      NetworkTest.NetworkTester nt = new NetworkTest.NetworkTester(msg_sizes,null,microseconds_collective,model_size>1e6 ? 1 : 5 /*repeats*/,false,true /*only collectives*/);
      nt.compute2();

      //length of the network traffic queue based on log-tree rollup (2 log(nodes))
      int network_queue_length = mp.single_node_mode || H2O.CLOUD.size() == 1? 1 : 2*(int)Math.floor(Math.log(H2O.CLOUD.size())/Math.log(2));

      // heuristics
      double flops_overhead_per_row = 30;
      if (mp.activation == Activation.Maxout || mp.activation == Activation.MaxoutWithDropout) {
        flops_overhead_per_row *= 8;
      } else if (mp.activation == Activation.Tanh || mp.activation == Activation.TanhWithDropout) {
        flops_overhead_per_row *= 5;
      }

      // target fraction of comm vs cpu time: 5%
      double fraction = mp.single_node_mode || H2O.CLOUD.size() == 1 ? 1e-3 : 0.05; //one single node mode, there's no model averaging effect, so less need to shorten the M/R iteration

      // estimate the time for communication (network) and training (compute)
      model.time_for_communication_us = (H2O.CLOUD.size() == 1 ? 1e4 /* add 10ms for single-node */ : 0) + network_queue_length * microseconds_collective[0];
      double time_per_row_us  = flops_overhead_per_row * model_size / (total_gflops * 1e9) / H2O.SELF._heartbeat._cpus_allowed * 1e6;

      // compute the optimal number of training rows per iteration
      // fraction := time_comm_us / (time_comm_us + tspi * time_per_row_us)  ==>  tspi = (time_comm_us/fraction - time_comm_us)/time_per_row_us
      tspi = (long)((model.time_for_communication_us / fraction - model.time_for_communication_us)/ time_per_row_us);

      tspi = Math.min(tspi, (mp.single_node_mode ? 1 : H2O.CLOUD.size()) * numRows * 10); //not more than 10x of what train_samples_per_iteration=-1 would do

      // If the number is close to a multiple of epochs, use that -> prettier scoring
      if (tspi > numRows && Math.abs(tspi % numRows)/(double)numRows < 0.2)  tspi = tspi - tspi % numRows;
      tspi = Math.min(tspi, (long)(mp.epochs * numRows / 10)); //limit to number of epochs desired, but at least 10 iterations total
      tspi = Math.max(1, tspi); //at least 1 point

      if (!mp.quiet_mode) {
        Log.info("Auto-tuning parameter 'train_samples_per_iteration':");
        Log.info("Estimated compute power : " + (int)total_gflops + " GFlops");
        Log.info("Estimated time for comm : " + PrettyPrint.usecs((long)model.time_for_communication_us));
        Log.info("Estimated time per row  : " + ((long)time_per_row_us > 0 ? PrettyPrint.usecs((long)time_per_row_us) : time_per_row_us + " usecs"));
        Log.info("Estimated training speed: " + (int)(1e6/time_per_row_us) + " rows/sec");
        Log.info("Setting train_samples_per_iteration (" + mp.train_samples_per_iteration + ") to auto-tuned value: " + tspi);
      }

    } else {
      // limit user-given value to number of epochs desired
      tspi = Math.min(tspi, (long)(mp.epochs * numRows));
    }
    assert(tspi != 0 && tspi != -1 && tspi != -2 && tspi >= 1);
    return tspi;
  }

  /**
   * Compute the fraction of rows that need to be used for training during one iteration
   * @param numRows number of training rows
   * @param train_samples_per_iteration number of training rows to be processed per iteration
   * @param replicate_training_data whether of not the training data is replicated on each node
   * @return fraction of rows to be used for training during one iteration
   */
  private static float computeRowUsageFraction(final long numRows, final long train_samples_per_iteration, final boolean replicate_training_data) {
    float rowUsageFraction = (float)train_samples_per_iteration / numRows;
    if (replicate_training_data) rowUsageFraction /= H2O.CLOUD.size();
    assert(rowUsageFraction > 0);
    return rowUsageFraction;
  }
  private static float rowFraction(Frame train, DeepLearning p, DeepLearningModel m) {
    return computeRowUsageFraction(train.numRows(), m.actual_train_samples_per_iteration, p.replicate_training_data);
  }

  /**
   * Cross-Validate a DeepLearning model by building new models on N train/test holdout splits
   * @param splits Frames containing train/test splits
   * @param cv_preds Array of Frames to store the predictions for each cross-validation run
   * @param offsets Array to store the offsets of starting row indices for each cross-validation run
   * @param i Which fold of cross-validation to perform
   */
  @Override public void crossValidate(Frame[] splits, Frame[] cv_preds, long[] offsets, int i) {
    // Train a clone with slightly modified parameters (to account for cross-validation)
    final DeepLearning cv = (DeepLearning) this.clone();
    cv.genericCrossValidation(splits, offsets, i);
    cv_preds[i] = ((DeepLearningModel) UKV.get(cv.dest())).score(cv.validation);
    new TAtomic<DeepLearningModel>() {
      @Override public DeepLearningModel atomic(DeepLearningModel m) {
        if (!keep_cross_validation_splits && /*paranoid*/cv.dest().toString().contains("xval")) {
          m.get_params().source = null;
          m.get_params().validation=null;
          m.get_params().response=null;
        }
        return m;
      }
    }.invoke(cv.dest());
  }
}
