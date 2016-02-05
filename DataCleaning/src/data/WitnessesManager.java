package data;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.logging.Logger;

import com.sun.org.apache.xml.internal.serialize.LineSeparator;

public class WitnessesManager {

	private List<Witness> m_witnesses;
	private List<Rule> m_rules;
	private String m_dbName;
	private final static Logger LOGGER = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);

	public WitnessesManager(List<Rule> rules, String dbName) {
		m_rules = rules;
		m_dbName = dbName;
		m_witnesses = new ArrayList<Witness>();
	}

	public void calculateWitnesses() {

		for (Rule rule : m_rules) {
			if (rule.isTupleGenerating()) {
				extractTGRWitnesses(rule);
			} else {
				extractCGRWitnesses(rule);
			}
		}
		addSourceWitnesses();
	}

	public List<Rule> getRules() {
		return m_rules;
	}

	public List<Witness> getWitnesses() {
		return m_witnesses;
	}

	public String getWitnessesStr() {
		String result = "";
		for (Witness w : m_witnesses) {
			result += w.toString() + System.lineSeparator();
		}
		return result;
	}

	public List<DBTuple> getSuspiciousTuples() {
		return getSuspiciousTuples(m_witnesses);
	}

	private List<DBTuple> getSuspiciousTuples(List<Witness> witnesses) {

		List<DBTuple> suspicious = new ArrayList<DBTuple>();

		for (Witness witness : witnesses) {
			for (DBTuple tuple : witness.getTuples()) {
				if (!suspicious.contains(tuple)) {
					suspicious.add(tuple);
				}
			}
		}
		return suspicious;
	}

	private void extractCGRWitnesses(Rule rule) {

		Connection conn = null;
		Statement stmt = null;
		try {
			Class.forName("org.sqlite.JDBC");
			conn = DriverManager.getConnection("jdbc:sqlite:" + m_dbName);

			stmt = conn.createStatement();
			String sql = rule.getFalseQuery();
			ResultSet rs = stmt.executeQuery(sql);

			while (rs.next()) {
				int rsColumnIndex = 1;
				List<DBTuple> tuples = new ArrayList<DBTuple>();
				for (int i = 0; i < rule.getLHSFormulaCount(); i++) {
					if (rule.getLHSFormulaAt(i) instanceof ConditionalFormula) {
						continue;
					}
					RelationalFormula formula = (RelationalFormula) rule.getLHSFormulaAt(i);
					DBTuple tuple = new DBTuple(formula.getTable(), false);
					for (int j = 0; j < formula.getVariableCount(); j++) {
						Variable var = formula.getVariableAt(j);
						String value = rs.getString(rsColumnIndex);
						tuple.setValue(var.getColumn(), value);
						rsColumnIndex++;
					}
					tuples.add(tuple);
				}
				Witness witness = new Witness(rule, tuples);
				m_witnesses.add(witness);

				String info = System.lineSeparator() + "-------------------------------" + System.lineSeparator();
				info += rule.toString() + System.lineSeparator();
				info += witness.toString() + System.lineSeparator();
				info += "-------------------------------" + System.lineSeparator();
				LOGGER.info(info);
			}

			rs.close();
			stmt.close();
			conn.close();
		} catch (Exception e) {
			System.err.println(e.getClass().getName() + ": " + e.getMessage());
			System.exit(0);
		}
	}

	private void extractTGRWitnesses(Rule rule) {

		Connection conn = null;
		Statement stmt = null;
		try {
			Class.forName("org.sqlite.JDBC");
			conn = DriverManager.getConnection("jdbc:sqlite:" + m_dbName);

			stmt = conn.createStatement();

			RelationalFormula rhsFormula = (RelationalFormula) rule.getRHSFormulaAt(0);
			String sql = rule.getFalseQuery();
			ResultSet rs = stmt.executeQuery(sql);

			while (rs.next()) {
				HashMap<String, String> assignment = new HashMap<String, String>();
				int rsColumnIndex = 1;
				List<DBTuple> tuples = new ArrayList<DBTuple>();
				for (int i = 0; i < rule.getLHSFormulaCount(); i++) {
					if (rule.getLHSFormulaAt(i) instanceof ConditionalFormula) {
						continue;
					}
					RelationalFormula lhsFormula = (RelationalFormula) rule.getLHSFormulaAt(i);
					DBTuple tuple = new DBTuple(lhsFormula.getTable(), false);
					for (int j = 0; j < lhsFormula.getVariableCount(); j++) {
						Variable var = lhsFormula.getVariableAt(j);
						String value = rs.getString(rsColumnIndex);
						tuple.setValue(var.getColumn(), value);
						assignment.put(var.getName(), value);
						rsColumnIndex++;
					}
					tuples.add(tuple);
				}

				// Add tuple for rhs formula with replacing rhs defined vars
				// with ".*"
				HashSet<String> rhsDefinedVars = rule.getRHSDefinedVariables();
				DBTuple tuple = new DBTuple(rhsFormula.getTable(), true);
				for (int j = 0; j < rhsFormula.getVariableCount(); j++) {
					Variable var = rhsFormula.getVariableAt(j);
					if (rhsDefinedVars.contains(var.getName())) {
						tuple.setValue(var.getColumn(), ".*");
					} else {
						tuple.setValue(var.getColumn(), assignment.get(var.getName()));
					}
				}
				tuples.add(tuple);

				Witness witness = new Witness(rule, tuples);
				m_witnesses.add(witness);

				String info = System.lineSeparator() + "-------------------------------" + System.lineSeparator();
				info += rule.toString() + System.lineSeparator();
				info += witness.toString() + System.lineSeparator();
				info += "-------------------------------" + System.lineSeparator();
				LOGGER.info(info);
			}

			rs.close();
			stmt.close();
			conn.close();
		} catch (Exception e) {
			System.err.println(e.getClass().getName() + ": " + e.getMessage());
			System.exit(0);
		}
	}

	private void addSourceWitnesses() {

		String info = "";
		List<DBTuple> currSuspicious = getSuspiciousTuples(m_witnesses);

		LOGGER.info("Adding source witnesses");
		LOGGER.info("Current suspicious tuples: " + getTuplesListStr(currSuspicious));
		for (Rule rule : m_rules) {
			if (!rule.isTupleGenerating()) {
				continue;
			}

			RelationalFormula formula = (RelationalFormula) rule.getRHSFormulaAt(0);
			String ruleRhsTable = formula.getTable();

			for (DBTuple suspiciousTuple : currSuspicious) {

				// Check if suspicious tuple table equals rule RHS formula table
				// && the tuple is not anonymous
				if (!suspiciousTuple.getTable().equals(ruleRhsTable) || suspiciousTuple.isAnonymous()) {
					continue;
				}
				
				HashSet<String> rhsDefinedVars = rule.getRHSDefinedVariables();
				String sql = rule.getSourceQuery();
				
				info = "Finding source of the tuple: " + suspiciousTuple.toString() + System.lineSeparator();
				info += "By the rule: " + rule.toString() + System.lineSeparator();
				info += "Using the Query: " + sql + System.lineSeparator();
				LOGGER.info(info);
				
				String newSql = "";
				int paramIndex = 1;
				for (int j = 0; j < formula.getVariableCount(); j++) {
					
					Variable var = formula.getVariableAt(j);
					if (rhsDefinedVars.contains(var.getName())) {
						continue;
					}

					info = "The RHS formula: " + formula.toString() + System.lineSeparator();
					info += "Replacing: " + "$" + paramIndex + " with " + suspiciousTuple.getValue(var.getColumn()) + System.lineSeparator();
					info += "Prev SQL query: " + sql + System.lineSeparator();
					
					if (var.getType().equals("String")) {
						newSql = sql.replace("$" + paramIndex, "'" + suspiciousTuple.getValue(var.getColumn()) + "'");
					} else {
						newSql = sql.replace("$" + paramIndex, suspiciousTuple.getValue(var.getColumn()));
					}
					info += "Next SQL query: " + newSql + System.lineSeparator();
					LOGGER.info(info);
					
					paramIndex++;
				}
				
				Connection conn = null;
				Statement stmt = null;
				try {
					Class.forName("org.sqlite.JDBC");
					conn = DriverManager.getConnection("jdbc:sqlite:" + m_dbName);

					stmt = conn.createStatement();
					ResultSet rs = stmt.executeQuery(newSql);

					while (rs.next()) {
						int rsColumnIndex = 1;
						List<DBTuple> tuples = new ArrayList<DBTuple>();
						for (int i = 0; i < rule.getLHSFormulaCount(); i++) {
							if (rule.getLHSFormulaAt(i) instanceof ConditionalFormula) {
								continue;
							}
							RelationalFormula lhsFormula = (RelationalFormula) rule.getLHSFormulaAt(i);
							DBTuple tuple = new DBTuple(lhsFormula.getTable(), false);
							for (int j = 0; j < lhsFormula.getVariableCount(); j++) {
								Variable var = lhsFormula.getVariableAt(j);
								String value = rs.getString(rsColumnIndex);
								tuple.setValue(var.getColumn(), value);
								rsColumnIndex++;
							}
							tuples.add(tuple);
						}

						tuples.add(suspiciousTuple);
						Witness witness = new Witness(rule, tuples);
						m_witnesses.add(witness);

						info = System.lineSeparator() + "-------------------------------" + System.lineSeparator();
						info += "A source of rule: " + rule.toString() + System.lineSeparator();
						info += witness.toString() + System.lineSeparator();
						info += "-------------------------------" + System.lineSeparator();
						LOGGER.info(info);
					}

					rs.close();
					stmt.close();
					conn.close();
				} catch (Exception e) {
					System.err.println(e.getClass().getName() + ": " + e.getMessage());
					System.exit(0);
				}
			}
		}

		List<DBTuple> newSuspicious = getSuspiciousTuples(m_witnesses);

		LOGGER.info("New suspicious tuples: " + getTuplesListStr(newSuspicious));
		if (!tuplesListsEqual(currSuspicious, newSuspicious)) {
			LOGGER.info("Current and New are different");
			addSourceWitnesses();
		}
		LOGGER.info("Current and New are the same");
		
		//TODO: Every thing is OK just don't add witnesses which exists.
	}
	
	private boolean tuplesListsEqual(List<DBTuple> first, List<DBTuple> second) {
		
		for (DBTuple firstListTuple : first) {
			
			if (!second.contains(firstListTuple)) {
				return false;
			}
		}
		
		for (DBTuple secondListTuple : second) {
			
			if (!first.contains(secondListTuple)) {
				return false;
			}
		}		
		
		return true;
	}
	
	private String getTuplesListStr(List<DBTuple> tuples) {
		String result = System.lineSeparator();
		for (DBTuple tuple : tuples) {
			result += tuple.toString() + System.lineSeparator();
		}
		return result;
	}
}