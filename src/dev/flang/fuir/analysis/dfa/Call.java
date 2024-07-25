/*

This file is part of the Fuzion language implementation.

The Fuzion language implementation is free software: you can redistribute it
and/or modify it under the terms of the GNU General Public License as published
by the Free Software Foundation, version 3 of the License.

The Fuzion language implementation is distributed in the hope that it will be
useful, but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public
License for more details.

You should have received a copy of the GNU General Public License along with The
Fuzion language implementation.  If not, see <https://www.gnu.org/licenses/>.

*/

/*-----------------------------------------------------------------------
 *
 * Tokiwa Software GmbH, Germany
 *
 * Source of class Call
 *
 *---------------------------------------------------------------------*/

package dev.flang.fuir.analysis.dfa;


import dev.flang.fuir.FUIR;


import dev.flang.ir.IR;

import dev.flang.util.ANY;
import dev.flang.util.Errors;
import static dev.flang.util.FuzionConstants.EFFECT_ABORTABLE_NAME;
import dev.flang.util.HasSourcePosition;
import dev.flang.util.List;
import dev.flang.util.Terminal;

import java.util.TreeSet;

/**
 * Call represents a call
 *
 * @author Fridtjof Siebert (siebert@tokiwa.software)
 */
public class Call extends ANY implements Comparable<Call>, Context
{


  /*-----------------------------  classes  -----------------------------*/


  /*----------------------------  constants  ----------------------------*/


  /*----------------------------  variables  ----------------------------*/


  int _id = -1;

  /**
   * The DFA instance we are working with.
   */
  final DFA _dfa;


  /**
   * The clazz this is calling.
   */
  final int _cc;


  /**
   * If available, _site gives the call site of this Call as used in the IR.
   * Calls with different call sites are analysed separately, even if the
   * context and environment of the call is the same.
   *
   * IR.NO_SITE if the call site is not known, i.e., the call is coming from
   * intrinsic call or the main entry point.
   */
  final int _site;
  int siteIndex()
  {
    return _site == FUIR.NO_SITE ? 0 : _site - FUIR.SITE_BASE + 1;
  }


  /**
   * Target value of the call
   */
  Value _target;


  /**
   * Arguments passed to the call.
   */
  List<Val> _args;


  /**
   * 'this' instance created by this call.
   */
  Value _instance;


  /**
   * true means that the call may return, false means the call has not been
   * found to return, i.e., the result is null (aka void).
   */
  boolean _returns = false;


  /**
   * The environment, i.e., the effects installed when this call is made.
   */
  final Env _originalEnv;

  // Env _effectiveEnvCache;


  /**
   * For debugging: Reason that causes this call to be part of the analysis.
   */
  Context _context;


  /**
   * True if this instance escapes this call.
   */
  boolean _escapes = false;


  boolean _scheduledForAnalysis = false;


  //  java.util.TreeSet<Integer> _requiredEffects = null;

  int _calledByAbortableForEffect = -1;


  /*---------------------------  constructors  ---------------------------*/


  /**
   * Create Call
   *
   * @param dfa the DFA instance we are analyzing with
   *
   * @param cc called clazz
   *
   * @param site the call site, -1 if unknown (from intrinsic or program entry
   * point)
   *
   * @param target is the target value of the call
   *
   * @param args are the actual arguments passed to the call
   *
   * @param context for debugging: Reason that causes this call to be part of
   * the analysis.
   */
  public Call(DFA dfa, int cc, int site, Value target, List<Val> args, Env env, Context context)
  {
    _dfa = dfa;
    _cc = cc;
    if (false &&
        dfa._fuir.clazzIsUnitType(cc))
      {
        site = IR.NO_SITE;
      }
    _site = site;
    _target = target;
    _args = args;
    _originalEnv = env;
    //    var re = dfa._requiredEffects.getIfExists(siteIndex());
    //    _requiredEffects = re;
    //    _effectiveEnvCache = env == null || re == null ? null : env.filter(_requiredEffects);
    _context = context;
    //    _instance = dfa.newInstance(cc, site, this);

    if (dfa._fuir.clazzResultField(cc)==-1) /* <==> _fuir.isConstructor(cl) */
      {
        /* a constructor call returns current as result, so it always escapes together with all outer references! */
        dfa.escapes(cc);
        var or = dfa._fuir.clazzOuterRef(cc);
        while (or != -1)
          {
            var orr = dfa._fuir.clazzResultClazz(or);
            dfa.escapes(orr);
            or = dfa._fuir.clazzOuterRef(orr);
          }
      }

  }


  /*-----------------------------  methods  -----------------------------*/


  boolean compareSite(Call other)
  {
    return
      (_args.size() != 0
       // || _dfa._fuir.clazzResultClazz(_cc) == _cc                                                             // works, total 33247 calls, 15.02
       // || !_dfa._fuir.clazzIsUnitType(_dfa._fuir.clazzResultClazz(_cc)) && !_dfa._fuir.clazzIsUnitType(_cc)   // works, total 27907 calls
       // || !_dfa._fuir.clazzIsUnitType(_dfa._fuir.clazzResultClazz(_cc))                                       // works, total 28024 calls
       // || !_dfa._fuir.clazzIsUnitType(_cc)                                                                    // works, total 27880 calls 8.92s
       || true                                                                                                // works, total 28712 calls, 8.98s
       ) &&
      _site != other._site;
  }

  /**
   * Compare this to another Call.
   */
  public int compareTo(Call other)
  {
    var r =
      _cc   <   other._cc  ? -1 :
      _cc   >   other._cc  ? +1 :
      _target._id != other._target._id ? Integer.compare(_target._id, other._target._id) :
      //      _dfa._fuir.clazzIsUnitType(_cc) ? 0 :
      //      (_dfa._fuir.clazzIsUnitType(_cc) || (_target == Value.UNIT && _dfa._fuir.clazzArgCount(_cc) == 0)) && _requiredEffects == null ? 0 :
      compareSite(other) ? Integer.compare(_site, other._site) : 0;
      //      Value.compare(_target, other._target);
    // Integer.compare(_target._id, other._target._id);
    if (false)
    for (var i = 0; r == 0 && i < _args.size(); i++)
      {
        r = Value.compare(      _args.get(i).value(),
                          other._args.get(i).value());
      }
    if (r == 0)
      {
        r = Env.compare(effectiveEnv(), other.effectiveEnv());
      }
    return r;
  }
  public String compareToWhy(Call other)
  {
    var r =
      _cc   <   other._cc  ? "cc-1" :
      _cc   >   other._cc  ? "cc+1" :
      _dfa._fuir.clazzIsUnitType(_cc) ? "unit" :
      (_dfa._fuir.clazzIsUnitType(_cc) || (_target == Value.UNIT && _dfa._fuir.clazzArgCount(_cc) == 0)) ?  "unit" :
      _site <   other._site? "ste-1" :
      _site >   other._site? "ste+1" : null;
    if (r == null && Value.compare(_target, other._target) != 0)
      {
        r = "Value.compare";
      }

    for (var i = 0; r == null && i < _args.size(); i++)
      {
        var r0 = Value.compare(      _args.get(i).value(),
                           other._args.get(i).value());
        r = r0 == 0 ? null : "arg#"+i;
      }
    if (r == null)
      {
        var r0 = Env.compare(_originalEnv, other._originalEnv);
        r = "ENV";
      }
    return r;
  }
  void mergeWith(List<Val> nargs)
  {
    for (var i = 0; i < _args.size(); i++)
      {
        var a0 = _args.get(i);
        var a1 = nargs.get(i);
        _args.set(i, a0.joinVal(_dfa, a1));
      }
    //_target = _target.join(_dfa, other._target);
  }


  /**
   * Record the fact that this call returns, i.e., it does not necessarily diverge.
   */
  void returns()
  {
    if (!_returns)
      {
        _returns = true;
        _dfa.wasChanged(() -> "Call.returns for " + this);
      }
  }


  /**
   * Return the result value returned by this call.  null in case this call
   * never returns.
   */
  public Val result()
  {
    Val result = null;
    if (_dfa._fuir.clazzKind(_cc) == IR.FeatureKind.Intrinsic)
      {
        var name = _dfa._fuir.clazzOriginalName(_cc);
        var idfa = DFA._intrinsics_.get(name);
        if (idfa != null)
          {
            result = DFA._intrinsics_.get(name).analyze(this);
          }
        else
          {
            var at = _dfa._fuir.clazzTypeParameterActualType(_cc);
            if (at >= 0)
              {
                var rc = _dfa._fuir.clazzResultClazz(_cc);
                var t = _dfa.newInstance(rc, _site, this);
                // NYI: DFA missing support for Type instance, need to set field t.name to tname.
                result = t;
              }
            else
              {
                var msg = "DFA: code to handle intrinsic '" + name + "' is missing";
                Errors.warning(msg);
                var rc = _dfa._fuir.clazzResultClazz(_cc);
                result = switch (_dfa._fuir.getSpecialClazz(rc))
                  {
                  case c_i8, c_i16, c_i32, c_i64,
                       c_u8, c_u16, c_u32, c_u64,
                       c_f32, c_f64              -> NumericValue.create(_dfa, rc);
                  case c_bool                    -> _dfa._bool;
                  case c_TRUE, c_FALSE           -> Value.UNIT;
                  case c_Const_String, c_String  -> _dfa.newConstString(null, this);
                  case c_unit                    -> Value.UNIT;
                  case c_sys_ptr                 -> new Value(_cc); // NYI: we might add a specific value for system pointers
                  case c_NOT_FOUND               -> null;
                  };
              }
          }
      }
    else if (_dfa._fuir.clazzKind(_cc) == IR.FeatureKind.Native)
      {
        var rc = _dfa._fuir.clazzResultClazz(_cc);
        result = switch (_dfa._fuir.getSpecialClazz(rc))
          {
            case c_i8, c_i16, c_i32, c_i64,
                 c_u8, c_u16, c_u32, c_u64,
                 c_f32, c_f64              -> NumericValue.create(_dfa, rc);
            case c_Const_String, c_String  -> _dfa.newConstString(null, this);
            default                        -> { Errors.warning("DFA: cannot handle native feature " + _dfa._fuir.clazzOriginalName(_cc));
                                                yield null; }
          };
      }
    else if (_returns)
      {
        var rf = _dfa._fuir.clazzResultField(_cc);
        if (rf == -1)
          {
            result = _instance;
          }
        else if (FUIR.SpecialClazzes.c_unit == _dfa._fuir.getSpecialClazz(_dfa._fuir.clazzResultClazz(rf)))
          {
            result = Value.UNIT;
          }
        else
          {
            // should not be possible to return void (_result should be null):
            if (CHECKS) check
              (!_dfa._fuir.clazzIsVoidType(_dfa._fuir.clazzResultClazz(_cc)));

            result = _instance.readField(_dfa, rf, -1, this);
            if (false) if (_dfa._fuir.clazzAsString(_cc).startsWith("i#1") && !_rec)
              {
                _rec = true;
                System.out.println("CALL result of "+this);
                System.out.println(Terminal.INTENSE_BOLD_GREEN + result + Terminal.RESET);
                _rec = false;
              }
          }
      }
    return result;
  }
  boolean _rec;


  /**
   * Create human-readable string from this call.
   */
  boolean _call_toString_recursion = false;
  public String toString()
  {
    var result = "--?recurive Call.toString?--";
    if (!_call_toString_recursion)
      {
        _call_toString_recursion = true;
        var sb = new StringBuilder();
        sb.append(_dfa._fuir.clazzAsString(_cc));
        if (_target != Value.UNIT)
          {
            sb.append(" target=")
              .append(_target);
          }
        for (var i = 0; i < _args.size(); i++)
          {
            var a = _args.get(i);
            sb.append(" a")
              .append(i)
              .append("=")
              .append(a);
          }
        var r = result();
        sb.append(" => ")
          .append(r == null ? "*** VOID ***" : r)
          .append(" ENV0: ")
          .append(Errors.effe(Env.envAsString(_originalEnv)))
          .append(" ENV: ")
          .append(Errors.effe(Env.envAsString(effectiveEnv())));
        result = sb.toString();
        _call_toString_recursion = false;
      }
    return result;
  }


  /**
   * Create human-readable string from this call with effect environment information
   */
  public String toString(boolean forEnv)
  {
    var on = _dfa._fuir.clazzOriginalName(_cc);
    var pos = callSitePos();
    return
      (forEnv
       ? (on.equals(EFFECT_ABORTABLE_NAME)
          ? "install effect " + Errors.effe(_dfa._fuir.clazzAsStringHuman(_dfa._fuir.effectType(_cc))) + ", old environment was "
          : "effect environment ") +
         Errors.effe(Env.envAsString(effectiveEnv())) +
         " for call to "
       : "call ")+
      Errors.sqn(_dfa._fuir.clazzAsStringHuman(_cc)) +
      (pos != null ? " at " + pos.pos().show() : "");
  }


  /**
   * Show the context that caused the inclusion of this call into the analysis.
   *
   * @param sb the context information will be appended to this StringBuilder.
   *
   * @return a string providing the indentation level for the caller in case of
   * nested contexts.  "  " is to be added to the result on each recursive call.
   */
  public String showWhy(StringBuilder sb)
  {
    var indent = _context.showWhy(sb);
    var pos = callSitePos();
    sb.append(indent).append("  |\n")
      .append(indent).append("  +- performs call ").append(this).append("\n")
      .append(pos != null ? pos.pos().show() + "\n"
                          : "");
    return indent + "  ";
  }


  /**
   * return the call site index, -1 if unknown.
   */
  int site()
  {
    return _site;
  }


  /**
   * If available, get a source code position of a call site that results in this call.
   *
   * @return The SourcePosition or null if not available
   */
  HasSourcePosition callSitePos()
  {
    var s = site();
    return s == IR.NO_SITE ? null
                           : _dfa._fuir.sitePos(s);
  }


  Env effectiveEnv()
  {
    return _originalEnv;
  }


  /**
   * Get effect of given type in this call's environment or the default if none
   * found or null if no effect in environment and also no default available.
   *
   * @param ecl clazz defining the effect type.
   *
   * @return null in case no effect of type ecl was found
   */
  Value getEffectCheck(int ecl)
  {
    return
      _originalEnv != null ? _originalEnv.getActualEffectValues(ecl)
                           : _dfa._defaultEffects.get(ecl);
  }


  /**
   * Get effect of given type in this call's environment or the default if none
   * found or null if no effect in environment and also no default available.
   *
   * Report an error if no effect found during last pass (i.e.,
   * _dfa._reportResults is set).
   *
   * @param s site of the code requiring the effect
   *
   * @param ecl clazz defining the effect type.
   *
   * @return null in case no effect of type ecl was found
   */
  Value getEffectForce(int s, int ecl)
  {
    var result = getEffectCheck(ecl);
    if (result == null && _dfa._reportResults && !_dfa._fuir.clazzOriginalName(_cc).equals("effect.type.unsafe_get"))
      {
        DfaErrors.usedEffectNotInstalled(_dfa._fuir.sitePos(s),
                                         _dfa._fuir.clazzAsString(ecl),
                                         this);
        _dfa._missingEffects.add(ecl);
      }
    return result;
  }


  /**
   * Replace effect of given type with a new value.
   *
   * NYI: This currently modifies the effect and hence the call. We should check
   * how this could be avoided or handled better.
   *
   * @param ecl clazz defining the effect type.
   *
   * @param e new instance of this effect
   */
  void replaceEffect(int ecl, Value e)
  {
    if (_originalEnv != null)
      {
        _originalEnv.replaceEffect(ecl, e);
      }
    else
      {
        _dfa.replaceDefaultEffect(ecl, e);
      }
  }


  /**
   * Record that the instance of this call escapes, i.e., it might be accessed
   * after the call returned and cannot be allocated on the stack.
   */
  void escapes()
  {
    if (PRECONDITIONS) require
      (_dfa._fuir.clazzKind(_cc) == FUIR.FeatureKind.Routine);

    if (!_escapes)
      {
        _escapes = true;
        // we currently store for _cc, so we accumulate different call
        // contexts to the same clazz. We might make this more detailed and
        // record this local to the call or use part of the call's context like
        // the target value to be more accurate.
        _dfa.escapes(_cc);
      }
  }


}

/* end of file */
