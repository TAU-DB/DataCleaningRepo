package data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Rule {

	private String m_type;
	private String m_falseQuery;
	private String m_trueQuery;
	private String m_sourceQuery;
	private List<Formula> m_lhs;
	private List<Formula> m_rhs;

	public Rule(String type, String falseQuery, String trueQuery, String sourceQuery, List<Formula> lhs,
			List<Formula> rhs) {
		m_type = type;
		m_falseQuery = falseQuery;
		m_trueQuery = trueQuery;
		m_sourceQuery = sourceQuery;
		m_lhs = lhs;
		m_rhs = rhs;
	}

	public boolean isTupleGenerating() {
		if (m_type.equals("condition_generating")) {
			return false;
		}
		return true;
	}

	public String getFalseQuery() {
		return m_falseQuery;
	}

	public String getTrueQuery() {
		return m_trueQuery;
	}

	public String getSourceQuery() {
		return m_sourceQuery;
	}

	public int getLHSFormulaCount() {
		return m_lhs.size();
	}

	public Formula getLHSFormulaAt(int index) {
		return m_lhs.get(index);
	}

	public int getRHSFormulaCount() {
		return m_rhs.size();
	}

	public Formula getRHSFormulaAt(int index) {
		return m_rhs.get(index);
	}

	@Override
	public String toString() {
		String lhs = "";
		for (int i = 0; i < m_lhs.size(); i++) {
			if (i != m_lhs.size() - 1) {
				lhs += m_lhs.get(i).toString() + " && ";
			} else {
				lhs += m_lhs.get(i).toString();
			}
		}

		String rhs = "";
		for (int i = 0; i < m_rhs.size(); i++) {
			if (i != m_rhs.size() - 1) {
				rhs += m_rhs.get(i).toString() + " && ";
			} else {
				rhs += m_rhs.get(i).toString();
			}
		}

		String result = lhs + " --> " + rhs;
		return result;
	}

	public String toDetailedString() {

		String result = toString() + System.lineSeparator();

		result += m_falseQuery + System.lineSeparator() + m_trueQuery + System.lineSeparator() + m_sourceQuery
				+ System.lineSeparator() + "LHS variables:" + System.lineSeparator();
		for (Formula formula : m_lhs) {
			for (int i = 0; i < formula.getVariableCount(); i++) {
				result += formula.getVariableAt(i).toDetailedString() + System.lineSeparator();
			}
		}
		result += "RHS variables:" + System.lineSeparator();
		for (Formula formula : m_rhs) {
			for (int i = 0; i < formula.getVariableCount(); i++) {
				result += formula.getVariableAt(i).toDetailedString() + System.lineSeparator();
			}
		}

		return result;
	}

	public HashSet<String> getRHSDefinedVariables() {

		HashSet<String> rhsDefinedVars = new HashSet<String>();
		HashSet<String> lhsDefinedVars = new HashSet<String>();
		for (Formula formula : m_lhs) {
			for (int i = 0; i < formula.getVariableCount(); i++) {
				Variable var = formula.getVariableAt(i);
				lhsDefinedVars.add(var.getName());
			}
		}
		for (Formula formula : m_rhs) {
			for (int i = 0; i < formula.getVariableCount(); i++) {
				Variable var = formula.getVariableAt(i);
				if (!lhsDefinedVars.contains(var.getName())) {
					rhsDefinedVars.add(var.getName());
				}
			}
		}

		return rhsDefinedVars;
	}

	public List<String> getConditionalColumns(int relationalFormulaIndex) {
		
		List<String> result = new ArrayList<String>();
		int currRFIndex = 0;
		RelationalFormula relFormula = null;
		List<Formula> formulas = new ArrayList<Formula>();
		formulas.addAll(m_lhs);
		formulas.addAll(m_rhs);
		for (Formula formula : formulas) {
			if (formula instanceof RelationalFormula) {
				if (currRFIndex == relationalFormulaIndex) {
					relFormula = (RelationalFormula) formula;
				}
				currRFIndex++;
			}
		}

		for (int i = 0; i < relFormula.getVariableCount(); i++) {
			Variable RFvar = relFormula.getVariableAt(i);
			if (RFvar.isConstant()) {
				System.exit(0);
				System.out.println("FATAL in getConditionalColumns");
			}

			for (Formula formula : formulas) {
				if (formula instanceof RelationalFormula) {
					continue;
				}

				for (int j = 0; j < formula.getVariableCount(); j++) {
					Variable CFVar = formula.getVariableAt(j);
					if (!CFVar.isConstant() && CFVar.getName().equals(RFvar.getName())) {
						result.add(RFvar.getColumn());
					}
				}
			}
		}

		// If it's a tuple generating rule and the variable appears in both rule
		// sides then it's column is also conditional column
		if (isTupleGenerating()) {
			for (int i = 0; i < relFormula.getVariableCount(); i++) {
				Variable RFVar = relFormula.getVariableAt(i);
				if (!RFVar.isConstant() && isVariableInBothSides(RFVar.getName())
						&& !result.contains(RFVar.getColumn())) {
					result.add(RFVar.getColumn());
				}
			}
		}
		
		return result;
	}

	public Rule toNFRule() {
		if (!isTupleGenerating()) {
			return new Rule(m_type, m_falseQuery, m_trueQuery, m_sourceQuery, m_lhs, m_rhs);
		}

		Set<String> rhsDefinedVars = getRHSDefinedVariables();
		RelationalFormula formula = (RelationalFormula) m_rhs.get(0);
		HashMap<String, Variable> varMap = new HashMap<String, Variable>();
		int tempIndex = 0;
		for (int i = 0; i < formula.getVariableCount(); i++) {
			Variable var = formula.getVariableAt(i);
			if (var.isConstant()) {
				System.exit(0);
			}

			// BUG if variable appears in two columns in RHS formula
			if (!rhsDefinedVars.contains(var.getName())) {
				varMap.put(var.getName(),
						new Variable("temp_" + tempIndex, var.getColumn(), false, "temp_" + tempIndex, var.getType()));
				tempIndex++;
			}
		}
		List<Variable> newVariables = new ArrayList<Variable>();
		for (int i = 0; i < formula.getVariableCount(); i++) {
			Variable var = formula.getVariableAt(i);
			if (varMap.containsKey(var.getName())) {
				newVariables.add(varMap.get(var.getName()));
			} else {
				newVariables.add(var);
			}
		}

		List<Formula> newRHS = new ArrayList<Formula>();
		RelationalFormula newRHSFormula = new RelationalFormula(formula.getTable(), newVariables);
		newRHS.add(newRHSFormula);
		for (String varName : varMap.keySet()) {
			Variable tempVar = varMap.get(varName);
			List<Variable> conFormulaVars = new ArrayList<Variable>();
			conFormulaVars.add(tempVar);
			conFormulaVars.add(new Variable(varName, tempVar.getColumn(), false, varName, tempVar.getType()));
			ConditionalFormula conFormula = new ConditionalFormula(conFormulaVars, "==");
			newRHS.add(conFormula);
		}

		return new Rule(m_type, m_falseQuery, m_trueQuery, m_sourceQuery, m_lhs, newRHS);
	}

	private int getVariableCount() {

		List<Formula> formulas = new ArrayList<Formula>();
		formulas.addAll(m_lhs);
		formulas.addAll(m_rhs);
		List<String> variables = new ArrayList<String>();
		for (Formula formula : m_lhs) {
			for (int i = 0; i < formula.getVariableCount(); i++) {
				Variable var = formula.getVariableAt(i);
				if (var.isConstant()) {
					continue;
				}
				if (!variables.contains(var.getName())) {
					variables.add(var.getName());
				}
			}
		}
		return variables.size();
	}

	private boolean isVariableInBothSides(String varName) {

		boolean isVarInLHS = false;
		for (Formula formula : m_lhs) {
			if (formula.containsVariable(varName)) {
				isVarInLHS = true;
			}
		}

		boolean isVarInRHS = false;
		for (Formula formula : m_rhs) {
			if (formula.containsVariable(varName)) {
				isVarInRHS = true;
			}
		}

		if (isVarInLHS && isVarInRHS) {
			return true;
		}

		return false;
	}
}
