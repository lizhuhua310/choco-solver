/**
 * This file is part of choco-solver, http://choco-solver.org/
 *
 * Copyright (c) 2018, IMT Atlantique. All rights reserved.
 *
 * Licensed under the BSD 4-clause license.
 * See LICENSE file in the project root for full license information.
 */
package org.chocosolver.solver.constraints.nary.clauses;

import gnu.trove.map.hash.TIntObjectHashMap;

import org.chocosolver.solver.Model;
import org.chocosolver.solver.variables.BoolVar;
import org.chocosolver.solver.variables.IntVar;
import org.chocosolver.solver.variables.view.BoolNotView;
import org.chocosolver.solver.variables.view.EqView;
import org.chocosolver.solver.variables.view.LeqView;
import org.chocosolver.util.objects.setDataStructures.iterable.IntIterableRangeSet;
import org.chocosolver.util.objects.setDataStructures.iterable.IntIterableSetUtils;
import org.chocosolver.util.tools.VariableUtils;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.Stack;
import java.util.Vector;

/**
 * A signed clause builder
 *
 * <p> Project: choco-solver.
 *
 * @author Charles Prud'homme
 * @since 12/10/2016.
 */
public class ClauseBuilder {

    @SuppressWarnings("WeakerAccess")
    public static boolean ELIMINATE_VIEWS = true;
    /**
     * When the nogood is true (based on variables declared domain)
     */
    private static short ALWAYSTRUE = 0b10;
    /**
     * * When the nogood is unknown
     */
    private static short UNKNOWN = 0b01;
    /**
     * When the nogood is false (based on variables declared domain)
     */
    private static short FALSE = 0b00;

    /**
     * Sets of variables
     */
    private Set<IntVar> vars;
    /**
     * Sets of forbidden values
     */
    private final TIntObjectHashMap<IntIterableRangeSet> sets;
    /**
     * Status of the nogood to build
     */
    private short status = FALSE;
    /**
     * Initial state of the variables
     */
    private final TIntObjectHashMap<IntIterableRangeSet> initialDomains;

    /**
     * Nogood builder, to ease declaration of nogoods
     *
     * @param mModel model to declare the nogoods in
     */
    public ClauseBuilder(Model mModel) {
        vars = new HashSet<>();
        sets = new TIntObjectHashMap<>();
        initialDomains = new TIntObjectHashMap<>();
        Arrays.stream(mModel.retrieveIntVars(true))
                .forEach(v -> initialDomains.put(v.getId(), IntIterableSetUtils.extract(v)));
    }

    /**
     * Add a literal (var &isin; set) in this, considering that the entry <b>is only added once</b> (no
     * need to perform internal operations).
     * The set can be recycle <b>only after the call to {@link #buildNogood(Model)}</b>
     *
     * @param var a variable
     * @param set a set of values
     * @return the nogood maker
     */
    public ClauseBuilder put(IntVar var, IntIterableRangeSet set) {
        status |= UNKNOWN;
        if (var.isAConstant()) {
            if (!set.contains(var.getValue())) { //always true and ignore
                status |= ALWAYSTRUE;
            } // else, always false and ignore
            return this;
        }
        if (IntIterableSetUtils.intersect(set, initialDomains.get(var.getId()))) {
            if (IntIterableSetUtils.includedIn(initialDomains.get(var.getId()), set)) {
                status |= ALWAYSTRUE;
            }
            this.vars.add(var);
            this.sets.put(var.getId(), set);
        }
        return this;
    }

    public IntIterableRangeSet getInitialDomain(IntVar var) {
        return initialDomains.get(var.getId());
    }

    /**
     * Build the nogood in memory and post it to <i>model</i>
     *
     * @return the contraint or null if always true
     */
    public void buildNogood(Model model) {
        if ((status & ALWAYSTRUE) == 0) {
            if ((status & UNKNOWN) != 0) { // at least one clause is unknown
                if (ELIMINATE_VIEWS) eliminateViews();
                vars.removeIf(var -> (sets.get(var.getId()).isEmpty()));
                IntVar[] _vars = vars.toArray(new IntVar[vars.size()]);
                Arrays.sort(_vars); // to avoid undeterministic behavior
                switch (vars.size()) {
                    case 0:
                        model.falseConstraint().post();
                        break;
                    case 1:
                        model.member(_vars[0], sets.get(_vars[0].getId())).post();
                        break;
                    default:
                        IntIterableRangeSet[] ranges = new IntIterableRangeSet[_vars.length];
                        for (int i = 0; i < _vars.length; i++) {
                            ranges[i] = sets.get(_vars[i].getId());
                        }
                        model.getClauseConstraint().addClause(_vars, ranges);
                        break;
                }
            } else {
                // always false
                model.falseConstraint().post();
                throw new UnsupportedOperationException();
            }
        }//  else post nothing

        this.status = FALSE;
        this.vars.clear();
        this.sets.clear();
    }

    private void eliminateViews() {
        Stack<IntVar> keys = vars
                .stream()
                .filter(VariableUtils::isView)
                .collect(
                        Stack<IntVar>::new,
                        Stack::push,
                        Vector::addAll
                );
        while (!keys.isEmpty()) {
            IntVar v = keys.pop();
            if (v instanceof EqView) {
                eliminateEqView((EqView) v, keys);
            } else if (v instanceof LeqView) {
                eliminateLeqView((LeqView) v, keys);
            } else if (v instanceof BoolNotView) {
                eliminateNotView((BoolNotView) v, keys);
            }
        }
    }

    private void eliminateEqView(EqView ev, Stack<IntVar> keys) {
        IntIterableRangeSet set = sets.get(ev.getId());
        IntVar vv = ev.getVariable();
        assert set.size() == 1;
        if (set.min() == 1) {
            if (vars.contains(vv)) {
                sets.get(vv.getId()).add(ev.cste);
            } else {
                put(vv, new IntIterableRangeSet(ev.cste));
                if (VariableUtils.isView(vv)) {
                    keys.push(vv);
                }
            }
        } else if (set.min() == 0) {
            if (vars.contains(vv)) {
                sets.get(vv.getId()).remove(ev.cste);
            } else {
                IntIterableRangeSet es = initialDomains.get(vv.getId()).duplicate();
                es.remove(ev.cste);
                put(vv, es);
                if (VariableUtils.isView(vv)) {
                    keys.push(vv);
                }
            }
        }
        set.clear();
    }

    private void eliminateLeqView(LeqView lv, Stack<IntVar> keys) {
        IntIterableRangeSet set = sets.get(lv.getId());
        IntVar vv = lv.getVariable();
        assert set.size() == 1;
        if (set.min() == 1) {
            IntIterableRangeSet root = initialDomains.get(vv.getId()).duplicate();
            root.retainBetween(IntIterableRangeSet.MIN, lv.cste);
            if (vars.contains(vv)) {
                sets.get(vv.getId()).addAll(root);
            } else {
                put(vv, root);
                if (VariableUtils.isView(vv)) {
                    keys.push(vv);
                }
            }
        } else if (set.min() == 0) {
            if (vars.contains(vv)) {
                sets.get(vv.getId()).removeBetween(IntIterableRangeSet.MIN, lv.cste);
            } else {
                IntIterableRangeSet es = initialDomains.get(vv.getId()).duplicate();
                es.removeBetween(IntIterableRangeSet.MIN, lv.cste);
                put(vv, es);
                if (VariableUtils.isView(vv)) {
                    keys.push(vv);
                }
            }
        }
        set.clear();
    }

    private void eliminateNotView(BoolNotView nv, Stack<IntVar> keys) {
        IntIterableRangeSet set = sets.get(nv.getId());
        BoolVar vv = nv.not();
        assert set.size() == 1;
        int value = set.min();
        if (vars.contains(vv)) {
            sets.get(vv.getId()).add(1 - value);
        } else {
            put(vv, new IntIterableRangeSet(1 - value));
            if (VariableUtils.isView(vv)) {
                keys.push(vv);
            }
        }
        set.clear();
    }
}
