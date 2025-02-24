import h2o_cmd, h2o, h2o_util, h2o_gbm
import re, random, math

def pickRandGlmParams(paramDict, params):
    colX = 0
    randomGroupSize = random.randint(1,len(paramDict))
    for i in range(randomGroupSize):
        randomKey = random.choice(paramDict.keys())
        randomV = paramDict[randomKey]
        randomValue = random.choice(randomV)
        params[randomKey] = randomValue
        if (randomKey=='x'):
            colX = randomValue

        # force legal family/ink combos
        if 'family' in params and 'link' in params: 
            if params['family'] is not None:
                if params['family'] == 'poisson':
                    if params['link'] is not None and params['link'] not in ('identity', 'log', 'inverse', 'familyDefault'):
                        params['link'] = None 
                # only tweedie/tweedie is legal?
                if params['family'] == 'tweedie':
                    if params['link'] is not None and params['link'] not in ('tweedie'):
                        params['link'] = None
                if params['family'] == 'binomial':
                    if params['link'] is not None and params['link'] not in ('logit', 'identity', 'log', 'inverse', 'familyDefault'):
                        params['link'] = None
                if params['family'] == 'gaussian':
                    if params['link'] is not None and params['link'] not in ('logit', 'identity', 'log', 'inverse', 'familyDefault'):
                        params['link'] = None

        # case only used if binomial? binomial is default if no family
        # update: apparently case and case_mode always affect things
        # make sure the combo of case and case_mode makes sense
        # there needs to be some entries in both effective cases
        if ('case_mode' in params):
            if ('case' not in params) or (params['case'] is None):
                params['case'] = 1
            else:
                maxCase = max(paramDict['case'])
                minCase = min(paramDict['case'])
                if params['case_mode']=="<" and params['case']==minCase:
                    params['case'] += 1
                elif params['case_mode']==">" and params['case']==maxCase:
                    params['case'] -= 1
                elif params['case_mode']==">=" and params['case']==minCase:
                    params['case'] += 1
                elif params['case_mode']=="<=" and params['case']==maxCase:
                    params['case'] -= 1

    return colX


def simpleCheckGLMScore(self, glmScore, family='gaussian', allowFailWarning=False, **kwargs):
    warnings = None
    if 'warnings' in glmScore:
        warnings = glmScore['warnings']
        # stop on failed
        x = re.compile("failed", re.IGNORECASE)
        # don't stop if fail to converge
        c = re.compile("converge", re.IGNORECASE)
        for w in warnings:
            print "\nwarning:", w
            if re.search(x,w) and not allowFailWarning: 
                if re.search(c,w):
                    # ignore the fail to converge warning now
                    pass
                else: 
                    # stop on other 'fail' warnings (are there any? fail to solve?
                    raise Exception(w)

    validation = glmScore['validation']
    validation['err'] = h2o_util.cleanseInfNan(validation['err'])
    validation['nullDev'] = h2o_util.cleanseInfNan(validation['nullDev'])
    validation['resDev'] = h2o_util.cleanseInfNan(validation['resDev'])
    print "%15s %s" % ("err:\t", validation['err'])
    print "%15s %s" % ("nullDev:\t", validation['nullDev'])
    print "%15s %s" % ("resDev:\t", validation['resDev'])

    # threshold only there if binomial?
    # auc only for binomial
    if family=="binomial":
        print "%15s %s" % ("auc:\t", validation['auc'])
        print "%15s %s" % ("threshold:\t", validation['threshold'])

    err = False
    if family=="poisson" or family=="gaussian":
        if 'aic' not in validation:
            print "aic is missing from the glm json response"
            err = True

    if math.isnan(validation['err']):
        print "Why is this err = 'nan'?? %6s %s" % ("err:\t", validation['err'])
        err = True

    if math.isnan(validation['resDev']):
        print "Why is this resDev = 'nan'?? %6s %s" % ("resDev:\t", validation['resDev'])
        err = True

    if err:
        raise Exception ("How am I supposed to tell that any of these errors should be ignored?")

    # legal?
    if math.isnan(validation['nullDev']):
        ## emsg = "Why is this nullDev = 'nan'?? %6s %s" % ("nullDev:\t", validation['nullDev'])
        ## raise Exception(emsg)
        pass

def simpleCheckGLM(self, glm, colX, allowFailWarning=False, allowZeroCoeff=False,
    prettyPrint=False, noPrint=False, maxExpectedIterations=None, doNormalized=False, **kwargs):
    # if we hit the max_iter, that means it probably didn't converge. should be 1-maxExpectedIter

    # h2o GLM will verboseprint the result and print errors. 
    # so don't have to do that
    # different when cross validation  is used? No trainingErrorDetails?
    GLMModel = glm['glm_model']
    if not GLMModel:
        raise Exception("GLMModel didn't exist in the glm response? %s" % h2o.dump_json(glm))

    warnings = None
    if 'warnings' in GLMModel and GLMModel['warnings']:
        warnings = GLMModel['warnings']
        # stop on failed
        x = re.compile("failed", re.IGNORECASE)
        # don't stop if fail to converge
        c = re.compile("converge", re.IGNORECASE)
        for w in warnings:
            print "\nwarning:", w
            if re.search(x,w) and not allowFailWarning: 
                if re.search(c,w):
                    # ignore the fail to converge warning now
                    pass
                else: 
                    # stop on other 'fail' warnings (are there any? fail to solve?
                    raise Exception(w)

    # for key, value in glm.iteritems(): print key
    # not in GLMGrid?

    # FIX! don't get GLMParams if it can't solve?
    GLMParams = GLMModel['glm']
    family = GLMParams["family"]

    # number of submodels = number of lambda
    # min of 2. lambda_max is first
    submodels = GLMModel['submodels']
    # since all our tests?? only use one lambda, the best_lamda_idx should = 1
    best_lambda_idx = GLMModel['best_lambda_idx']
    print "best_lambda_idx:", best_lambda_idx
    lambda_max = GLMModel['lambda_max']
    print "lambda_max:", lambda_max

    # currently lambda_max is not set by tomas. ..i.e.not valid
    if 1==0 and (lambda_max <= submodels[best_lambda_idx].lambda_value):
        raise Exception("lambda_max %s should always be > the lambda result %s we're checking" % (lambda_max, submodels[best_lambda_idx].lambda_value))

    # submodels0 = submodels[0]
    # submodels1 = submodels[-1] # hackery to make it work when there's just one

    if (best_lambda_idx >= len(submodels)) or (best_lambda_idx < 0):
        raise Exception("best_lambda_idx: %s should point to one of lambdas (which has len %s)" % (best_lambda_idx, len(submodels)))

    if (best_lambda_idx >= len(submodels)) or (best_lambda_idx < 0):
        raise Exception("best_lambda_idx: %s should point to one of submodels (which has len %s)" % (best_lambda_idx, len(submodels)))

    submodels1 = submodels[best_lambda_idx] # hackery to make it work when there's just one
    iterations = submodels1['iteration']


    print "GLMModel/iterations:", iterations

            # if we hit the max_iter, that means it probably didn't converge. should be 1-maxExpectedIter
    if maxExpectedIterations is not None and iterations  > maxExpectedIterations:
            raise Exception("Convergence issue? GLM did iterations: %d which is greater than expected: %d" % (iterations, maxExpectedIterations) )

    if 'validation' not in submodels1:
        raise Exception("Should be a 'validation' key in submodels1: %s" % h2o.dump_json(submodels1))
    validationsList = submodels1['validation']
    validations = validationsList
        
    # xval. compare what we asked for and what we got.
    n_folds = kwargs.setdefault('n_folds', None)

    print "GLMModel/validations"        
    validations['null_deviance'] = h2o_util.cleanseInfNan(validations['null_deviance'])
    validations['residual_deviance'] = h2o_util.cleanseInfNan(validations['residual_deviance'])        
    print "%15s %s" % ("null_deviance:\t", validations['null_deviance'])
    print "%15s %s" % ("residual_deviance:\t", validations['residual_deviance'])

    # threshold only there if binomial?
    # auc only for binomial
    if family=="binomial":
        print "%15s %s" % ("auc:\t", validations['auc'])
        best_threshold = validations['best_threshold']
        thresholds = validations['thresholds']
        print "%15s %s" % ("best_threshold:\t", best_threshold)

        # have to look up the index for the cm, from the thresholds list
        best_index = None

        # FIX! best_threshold isn't necessarily in the list. jump out if >=
        for i,t in enumerate(thresholds):
            if t >= best_threshold: # ends up using next one if not present
                best_index = i
                break
            
        assert best_index!=None, "%s %s" % (best_threshold, thresholds)
        print "Now printing the right 'best_threshold' %s from '_cms" % best_threshold

        # cm = glm['glm_model']['submodels'][0]['validation']['_cms'][-1]
        submodels = glm['glm_model']['submodels']
        cms = submodels[0]['validation']['_cms']
        assert best_index<len(cms), "%s %s" % (best_index, len(cms))
        # if we want 0.5..rounds to int
        # mid = len(cms)/2
        # cm = cms[mid]
        cm = cms[best_index]

        print "cm:", h2o.dump_json(cm['_arr'])
        predErr = cm['_predErr']
        classErr = cm['_classErr']
        # compare to predErr
        pctWrong = h2o_gbm.pp_cm_summary(cm['_arr']);
        print "predErr:", predErr
        print "calculated pctWrong from cm:", pctWrong
        print "classErr:", classErr

        # self.assertLess(pctWrong, 9,"Should see less than 9% error (class = 4)")

        print "\nTrain\n==========\n"
        print h2o_gbm.pp_cm(cm['_arr'])


    if family=="poisson" or family=="gaussian":
        print "%15s %s" % ("aic:\t", validations['aic'])

    coefficients_names = GLMModel['coefficients_names']
    # print "coefficients_names:", coefficients_names
    idxs = submodels1['idxs']
    print "idxs:", idxs
    coefficients_names = coefficients_names

    # always check both normalized and normal coefficients
    norm_beta = submodels1['norm_beta']
    # if norm_beta and len(coefficients_names)!=len(norm_beta):
    #    print len(coefficients_names), len(norm_beta)
    #    raise Exception("coefficients_names and normalized_norm_beta from h2o json not same length. coefficients_names: %s normalized_norm_beta: %s" % (coefficients_names, norm_beta))
#
    beta = submodels1['beta']
    # print "beta:", beta
    # if len(coefficients_names)!=len(beta):
    #    print len(coefficients_names), len(beta)
    #    raise Exception("coefficients_names and beta from h2o json not same length. coefficients_names: %s beta: %s" % (coefficients_names, beta))


    # test wants to use normalized?
    if doNormalized:
        beta_used = norm_beta
    else:
        beta_used = beta

    coefficients = {}
    # create a dictionary with name, beta (including intercept) just like v1

    for i,b in zip(idxs, beta_used[:-1]):
        name = coefficients_names[i]
        coefficients[name] = b

    print "len(idxs)", len(idxs), "len(beta_used)", len(beta_used)
    print  "coefficients:", coefficients
    print  "beta:", beta
    print  "norm_beta:", norm_beta

    coefficients['Intercept'] = beta_used[-1]
    print "len(coefficients_names)", len(coefficients_names)
    print "len(idxs)", len(idxs)
    print "idxs[-1]", idxs[-1]
    print "intercept demapping info:", \
        "coefficients_names[-i]:", coefficients_names[-1], \
        "idxs[-1]:", idxs[-1], \
        "coefficients_names[idxs[-1]]:", coefficients_names[idxs[-1]], \
        "beta_used[-1]:", beta_used[-1], \
        "coefficients['Intercept']", coefficients['Intercept']

    # last one is intercept
    interceptName = coefficients_names[idxs[-1]]
    if interceptName != "Intercept" or abs(beta_used[-1])<1e-26:
        raise Exception("'Intercept' should be last in coefficients_names and beta %s %s %s" %\
            (idxs[-1], beta_used[-1], "-"+interceptName+"-"))

    # idxs has the order for non-zero coefficients, it's shorter than beta_used and coefficients_names
    # new 5/28/14. glm can point to zero coefficients
    # for i in idxs:
    #     if beta_used[i]==0.0:
    ##        raise Exception("idxs shouldn't point to any 0 coefficients i: %s %s:" % (i, beta_used[i]))
    if len(idxs) > len(beta_used):
        raise Exception("idxs shouldn't be longer than beta_used %s %s" % (len(idxs), len(beta_used)))
    intercept = coefficients.pop('Intercept', None)

    # intercept demapping info: idxs[-1]: 54 coefficients_names[[idxs[-1]]: Intercept beta_used[-1]: -6.6866753099
    # the last one shoudl be 'Intercept' ?
    coefficients_names.pop()

    # have to skip the output col! get it from kwargs
    # better always be there!
    y = kwargs['response']

    # the dict keys are column headers if they exist...how to order those? new: use the 'coefficients_names'
    # from the response
    # Tomas created 'coefficients_names which is the coefficient list in order.
    # Just use it to index coefficients! works for header or no-header cases
    # I guess now we won't print the "None" cases for dropped columns (constant columns!)
    # Because Tomas doesn't get everything in 'coefficients_names' if dropped by GLMQuery before
    # he gets it? 
    def add_to_coefficient_list_and_string(c, cList, cString):
        if c in coefficients:
            cValue = coefficients[c]
            cValueString = "%s: %.5e   " % (c, cValue)
        else:
            print "Warning: didn't see '" + c + "' in json coefficient response.",\
                  "Inserting 'None' with assumption it was dropped due to constant column)"
            cValue = None
            cValueString = "%s: %s   " % (c, cValue)

        cList.append(cValue)
        # we put each on newline for easy comparison to R..otherwise keep condensed
        if prettyPrint: 
            cValueString = "H2O coefficient " + cValueString + "\n"
        # not mutable?
        return cString + cValueString

    # creating both a string for printing and a list of values
    cString = ""
    cList = []
    # print in order using col_names
    # coefficients_names is input only now..same for header or no header, or expanded enums
    for c in coefficients_names:
        cString = add_to_coefficient_list_and_string(c, cList, cString)

    if prettyPrint: 
        print "\nH2O intercept:\t\t%.5e" % intercept
        print cString
    else:
        if not noPrint:
            print "\nintercept:", intercept, cString

    print "\nTotal # of coefficients:", len(coefficients_names)

    # pick out the coefficent for the column we enabled for enhanced checking. Can be None.
    # FIX! temporary hack to deal with disappearing/renaming columns in GLM
    if (not allowZeroCoeff) and (colX is not None):
        absXCoeff = abs(float(coefficients[str(colX)]))
        self.assertGreater(absXCoeff, 1e-26, (
            "abs. value of GLM coefficients['" + str(colX) + "'] is " +
            str(absXCoeff) + ", not >= 1e-26 for X=" + str(colX)
            ))

    # intercept is buried in there too
    absIntercept = abs(float(intercept))
    self.assertGreater(absIntercept, 1e-26, (
        "abs. value of GLM coefficients['Intercept'] is " +
        str(absIntercept) + ", not >= 1e-26 for Intercept"
                ))

    # this is good if we just want min or max
    # maxCoeff = max(coefficients, key=coefficients.get)
    # for more, just invert the dictionary and ...
    if (len(coefficients)>0):
        maxKey = max([(abs(coefficients[x]),x) for x in coefficients])[1]
        print "H2O Largest abs. coefficient value:", maxKey, coefficients[maxKey]
        minKey = min([(abs(coefficients[x]),x) for x in coefficients])[1]
        print "H2O Smallest abs. coefficient value:", minKey, coefficients[minKey]
    else: 
        print "Warning, no coefficients returned. Must be intercept only?"

    # many of the GLM tests aren't single column though.
    # quick and dirty check: if all the coefficients are zero, 
    # something is broken
    # intercept is in there too, but this will get it okay
    # just sum the abs value  up..look for greater than 0

    # skip this test if there is just one coefficient. Maybe pointing to a non-important coeff?
    if (not allowZeroCoeff) and (len(coefficients)>1):
        s = 0.0
        for c in coefficients:
            v = coefficients[c]
            s += abs(float(v))

        self.assertGreater(s, 1e-26, (
            "sum of abs. value of GLM coefficients/intercept is " + str(s) + ", not >= 1e-26"
            ))

    print "submodels1, run_time (milliseconds):", submodels1['run_time']

    # shouldn't have any errors
    h2o.check_sandbox_for_errors()

    return (warnings, cList, intercept)


# compare this glm to last one. since the files are concatenations, 
# the results should be similar? 10% of first is allowed delta
def compareToFirstGlm(self, key, glm, firstglm):
    # if isinstance(firstglm[key], list):
    # in case it's not a list allready (err is a list)
    h2o.verboseprint("compareToFirstGlm key:", key)
    h2o.verboseprint("compareToFirstGlm glm[key]:", glm[key])
    # key could be a list or not. if a list, don't want to create list of that list
    # so use extend on an empty list. covers all cases?
    if type(glm[key]) is list:
        kList  = glm[key]
        firstkList = firstglm[key]
    elif type(glm[key]) is dict:
        raise Exception("compareToFirstGLm: Not expecting dict for " + key)
    else:
        kList  = [glm[key]]
        firstkList = [firstglm[key]]
        print "kbn:", kList, firstkList

    for k, firstk in zip(kList, firstkList):
        # delta must be a positive number ?
        delta = .1 * abs(float(firstk))
        msg = "Too large a delta (" + str(delta) + ") comparing current and first for: " + key
        self.assertAlmostEqual(float(k), float(firstk), delta=delta, msg=msg)
        self.assertGreaterEqual(abs(float(k)), 0.0, str(k) + " abs not >= 0.0 in current")


def simpleCheckGLMGrid(self, glmGridResult, colX=None, allowFailWarning=False, **kwargs):
# "grid": {
#    "destination_keys": [
#        "GLMGridResults__8222a49156af52532a34fb3ce4304308_0", 
#        "GLMGridResults__8222a49156af52532a34fb3ce4304308_1", 
#        "GLMGridResults__8222a49156af52532a34fb3ce4304308_2"
#   ]
# }, 
    destination_key = glmGridResult['grid']['destination_keys'][0]
    inspectGG = h2o.nodes[0].glm_view(destination_key)
    models = inspectGG['glm_model']['submodels']
    h2o.verboseprint("GLMGrid inspect GLMGrid model 0(best):", h2o.dump_json(models[0]))
    g = simpleCheckGLM(self, inspectGG, colX, allowFailWarning=allowFailWarning, **kwargs)
    # just to get some save_model testing
    for i,m in enumerate(glmGridResult['grid']['destination_keys']):
        print "Saving model", m, "to model"+str(i)
        h2o.nodes[0].save_model(model=m, path='model'+str(i), force=1)

    return g


# This gives me a comma separated x string, for all the columns, with cols with
# missing values, enums, and optionally matching a pattern, removed. useful for GLM
# since it removes rows with any col with NA

# get input from this.
#   (missingValuesDict, constantValuesDict, enumSizeDict, colTypeDict, colNameDict) = \
#                h2o_cmd.columnInfoFromInspect(parseResult['destination_key', 
#                exceptionOnMissingValues=False, timeoutSecs=300)

def goodXFromColumnInfo(y, 
    num_cols=None, missingValuesDict=None, constantValuesDict=None, enumSizeDict=None, 
    colTypeDict=None, colNameDict=None, keepPattern=None, key=None, 
    timeoutSecs=120, returnIgnoreX=False, noPrint=False, returnStringX=True):

    y = str(y)

    # if we pass a key, means we want to get the info ourselves here
    if key is not None:
        (missingValuesDict, constantValuesDict, enumSizeDict, colTypeDict, colNameDict) = \
            h2o_cmd.columnInfoFromInspect(key, exceptionOnMissingValues=False, 
            max_column_display=99999999, timeoutSecs=timeoutSecs)
        num_cols = len(colNameDict)

    # now remove any whose names don't match the required keepPattern
    if keepPattern is not None:
        keepX = re.compile(keepPattern)
    else:
        keepX = None

    x = range(num_cols)
    # need to walk over a copy, cause we change x
    xOrig = x[:]
    ignore_x = [] # for use by RF
    for k in xOrig:
        name = colNameDict[k]
        # remove it if it has the same name as the y output
        if str(k)== y: # if they pass the col index as y
            if not noPrint:
                print "Removing %d because name: %s matches output %s" % (k, str(k), y)
            x.remove(k)
            # rf doesn't want it in ignore list
            # ignore_x.append(k)
        elif name == y: # if they pass the name as y 
            if not noPrint:
                print "Removing %d because name: %s matches output %s" % (k, name, y)
            x.remove(k)
            # rf doesn't want it in ignore list
            # ignore_x.append(k)

        elif keepX is not None and not keepX.match(name):
            if not noPrint:
                print "Removing %d because name: %s doesn't match desired keepPattern %s" % (k, name, keepPattern)
            x.remove(k)
            ignore_x.append(k)

        # missing values reports as constant also. so do missing first.
        # remove all cols with missing values
        # could change it against num_rows for a ratio
        elif k in missingValuesDict:
            value = missingValuesDict[k]
            if not noPrint:
                print "Removing %d with name: %s because it has %d missing values" % (k, name, value)
            x.remove(k)
            ignore_x.append(k)

        elif k in constantValuesDict:
            value = constantValuesDict[k]
            if not noPrint:
                print "Removing %d with name: %s because it has constant value: %s " % (k, name, str(value))
            x.remove(k)
            ignore_x.append(k)

        # this is extra pruning..
        # remove all cols with enums, if not already removed
        elif k in enumSizeDict:
            value = enumSizeDict[k]
            if not noPrint:
                print "Removing %d %s because it has enums of size: %d" % (k, name, value)
            x.remove(k)
            ignore_x.append(k)

    if not noPrint:
        print "x has", len(x), "cols"
        print "ignore_x has", len(ignore_x), "cols"

    # this is probably used in 'cols" in v2, which can take numbers
    if returnStringX:
        x = ",".join(map(str, x))

    ignore_x = ",".join(map(lambda x: "C" + str(x+1), ignore_x))

    if not noPrint:
        print "\nx:", x
        print "\nignore_x:", ignore_x

    if returnIgnoreX:
        return ignore_x
    else:
        return x
