package Controllers;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import org.apache.commons.collections15.Transformer;
import org.json.simple.JSONObject;

import data.Condition;
import data.ConditionalFormula;
import data.DBTuple;
import data.Formula;
import data.Graph;
import data.RelationalFormula;
import data.Rule;
import data.RulesReader;
import data.Variable;
import data.Vertex;
import data.Witness;
import data.WitnessesManager;
import edu.uci.ics.jung.algorithms.scoring.PageRank;
import edu.uci.ics.jung.graph.DirectedSparseGraph;

public class MainController {

	public static String convertRanksMapToStr(HashMap<DBTuple, Double> ranksMap) {
		String ranksMapStr = "{";
		int vertexIndex = 0;
		for (DBTuple tuple : ranksMap.keySet()) {
			ranksMapStr += "\"" + tuple.toString() + "\"";
			ranksMapStr += " : " + ranksMap.get(tuple);

			if (vertexIndex < ranksMap.keySet().size() - 1) {
				ranksMapStr += ", ";
			}
			vertexIndex++;
		}
		ranksMapStr += "}";
		return ranksMapStr;
	}

	public static JSONObject convertRanksMapToJSONObject(HashMap<DBTuple, Double> ranksMap) {
		JSONObject result = new JSONObject();
		for (DBTuple tuple : ranksMap.keySet()) {
			result.put(tuple.toString(), ranksMap.get(tuple));
		}
		return result;
	}

	public static DBTuple getMaxRankTuple(HashMap<DBTuple, Double> ranksMap) {

		Double maxRank = 0.0;
		DBTuple maxTuple = null;
		for (DBTuple tuple : ranksMap.keySet()) {

			Double currRank = ranksMap.get(tuple);
			if (currRank > maxRank) {
				maxRank = currRank;
				maxTuple = tuple;
			}
		}
		return maxTuple;
	}

	private static MainController m_instance = null;

	private String m_dbName = null;
	private List<Rule> m_rules;
	private WitnessesManager m_witManager = null;
	private String m_validatedDBName = null;
	private HashMap<String, Integer> m_tableToID = null;
	private final Logger LOGGER = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);

	public static MainController getInstance() {
		if (m_instance == null) {
			m_instance = new MainController();
		}

		return m_instance;
	}

	private MainController() {

		LOGGER.setLevel(Level.OFF);
		FileHandler fh;

		try {
			File logFile = new File("C:/temp/DataCleaning.log");
			if (!logFile.exists()) {
				logFile.createNewFile();
			}

			// This block configure the logger with handler and formatter
			fh = new FileHandler("C:/temp/DataCleaning.log");
			LOGGER.addHandler(fh);
			SimpleFormatter formatter = new SimpleFormatter();
			fh.setFormatter(formatter);

		} catch (SecurityException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}

		m_tableToID = new HashMap<String, Integer>();
		buildSimpleDB();
		buildValidatedDB();

		RulesReader reader = new RulesReader("C:" + File.separator + "temp" + File.separator + "rules.xml");
		m_rules = reader.getRules();
		m_witManager = new WitnessesManager(reader.getRules(), m_dbName);
	}

	public int getTableID(String tableName) {
		return m_tableToID.get(tableName).intValue();
	}

	public String getValidatedDBName() {
		return m_validatedDBName;
	}

	public Graph generateGraph() {
		m_witManager.calculateWitnesses();

		Graph graph = new Graph();
		List<DBTuple> suspiciousTuples = m_witManager.getSuspiciousTuples();
		for (DBTuple tuple : suspiciousTuples) {
			graph.addVertex(new Vertex(tuple));
		}
		for (DBTuple tuple : suspiciousTuples) {
			List<Witness> tupleWitnesses = m_witManager.getTupleWitnesses(tuple);

			for (Witness tupleWitness : tupleWitnesses) {
				updateGraphEdges(graph, tuple, tupleWitness);
			}
		}

		graph.calculateEdgesProbabilities();

		return graph;
	}

	public HashMap<DBTuple, Double> calculateRanks(Graph graph) {
		HashMap<String, Double> transitionsWeights = new HashMap<String, Double>();
		HashMap<DBTuple, Double> ranksMap = new HashMap<DBTuple, Double>();

		DirectedSparseGraph<String, String> jungGraph = new DirectedSparseGraph<String, String>();
		for (Vertex v : graph.getVertecis()) {
			jungGraph.addVertex(v.getID());
		}

		for (Vertex v : graph.getVertecis()) {
			String srcVertex = v.getID();
			List<Vertex> neighbors = graph.getNeighbors(v);
			for (int i = 0; i < neighbors.size(); i++) {
				Vertex n = neighbors.get(i);
				String dstVertex = n.getID();
				Double transitionWeight = graph.getEdgeProbability(v, n);
				jungGraph.addEdge(srcVertex + "->" + dstVertex, srcVertex, dstVertex);
				transitionsWeights.put(srcVertex + "->" + dstVertex, transitionWeight);
			}

			if (graph.getSelfLoopPobability(v) != null && graph.getSelfLoopPobability(v) > 0) {
				jungGraph.addEdge(srcVertex + "->" + srcVertex, srcVertex, srcVertex);
				transitionsWeights.put(srcVertex + "->" + srcVertex, graph.getSelfLoopPobability(v));
			}
		}

		long start = System.currentTimeMillis();
		PageRank<String, String> ranker = new PageRank<String, String>(jungGraph,
				new EdgesTransformer(transitionsWeights), 0.2);
		ranker.setTolerance(0.001);
		ranker.setMaxIterations(200);
		ranker.evaluate();

		String info = System.lineSeparator();
		info += "Tolerance = " + ranker.getTolerance() + System.lineSeparator();
		info += "Dump factor = " + (1.00d - ranker.getAlpha()) + System.lineSeparator();
		info += "Max iterations = " + ranker.getMaxIterations() + System.lineSeparator();
		info += "PageRank computed in " + (System.currentTimeMillis() - start) + " ms" + System.lineSeparator();

		for (Vertex v : graph.getVertecis()) {
			ranksMap.put(v.getTuple(), ranker.getVertexScore(v.getID()));
			info += "Tuple: " + v.getID() + " rank: " + ranksMap.get(v.getTuple()) + System.lineSeparator();
		}

		LOGGER.info(info);

		return ranksMap;
	}

	public List<DBTuple> getAnonymousTuples() {
		List<DBTuple> anonymousTuples = new ArrayList<DBTuple>();
		List<DBTuple> suspiciousTuples = m_witManager.getSuspiciousTuples();
		for (DBTuple susTuple : suspiciousTuples) {
			if (susTuple.isAnonymous()) {
				anonymousTuples.add(susTuple);
			}
		}

		return anonymousTuples;
	}

	public boolean isAnonymousTupleValidated(DBTuple anonymousTuple) {

		List<Rule> causeRules = m_witManager.getCauseRules(anonymousTuple);
		for (Rule causeRule : causeRules) {

			HashSet<String> rhsDefinedVars = causeRule.getRHSDefinedVariables();
			String sql = causeRule.getSourceQuery();
			RelationalFormula formula = (RelationalFormula) causeRule.getRHSFormulaAt(0);
			String newSql = "";
			int paramIndex = 1;
			for (int j = 0; j < formula.getVariableCount(); j++) {

				Variable var = formula.getVariableAt(j);
				if (rhsDefinedVars.contains(var.getName())) {
					continue;
				}

				if (var.getType().equals("String")) {
					newSql = sql.replace("$" + paramIndex, "'" + anonymousTuple.getValue(var.getColumn()) + "'");
				} else {
					newSql = sql.replace("$" + paramIndex, anonymousTuple.getValue(var.getColumn()));
				}

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
					List<DBTuple> tuples = extractTuples(rs, causeRule, false);
					List<DBTuple> validatedDBTuples = extractValidatedDBTuples(tuples);
					boolean isLHSValidated = checkLHSValidated(validatedDBTuples, causeRule);
					if (isLHSValidated) {
						rs.close();
						stmt.close();
						conn.close();
						return true;
					}
				}

				rs.close();
				stmt.close();
				conn.close();
			} catch (Exception e) {
				System.err.println(e.getClass().getName() + ": " + e.getMessage());
				System.exit(0);
			}
		}

		return false;
	}

	public void setValidated(DBTuple tuple) {

		if (tuple.isAnonymous()) {
			System.out.println("FATAL in set validated because tuple is anonymous");
			System.exit(0);
		}

		int rowIndex = getRowIndex(tuple);
		try {
			Connection dbConn = null;
			Connection validatedDBConn = null;
			Statement stmt = null;
			Class.forName("org.sqlite.JDBC");
			dbConn = DriverManager.getConnection("jdbc:sqlite:" + m_dbName);
			validatedDBConn = DriverManager.getConnection("jdbc:sqlite:" + m_validatedDBName);

			stmt = validatedDBConn.createStatement();
			String sql = "UPDATE " + tuple.getTable() + " SET ";
			int columnIndex = 0;
			for (String column : tuple.getColumns()) {
				sql += column + "=1";

				if (columnIndex < tuple.getColumns().size() - 1) {
					sql += ", ";
				}
				columnIndex++;
			}
			sql += " WHERE rowid=" + rowIndex + ";";

			stmt.executeUpdate(sql);
			stmt.close();

			dbConn.close();
			validatedDBConn.close();
		} catch (Exception e) {
			System.err.println("In set validated " + e.getClass().getName() + ": " + e.getMessage());
			e.printStackTrace();
			System.exit(0);
		}

	}

	public void deleteTuple(DBTuple tuple) {

		if (tuple.isAnonymous()) {
			System.out.println("FATAL in set validated because tuple is anonymous");
			System.exit(0);
		}

		int rowIndex = getRowIndex(tuple);

		try {
			Connection dbConn = null;
			Connection validatedDBConn = null;
			Statement stmt = null;
			Class.forName("org.sqlite.JDBC");
			dbConn = DriverManager.getConnection("jdbc:sqlite:" + m_dbName);
			validatedDBConn = DriverManager.getConnection("jdbc:sqlite:" + m_validatedDBName);

			stmt = dbConn.createStatement();
			String sql = "DELETE FROM " + tuple.getTable() + " WHERE rowid=" + rowIndex + ";";
			stmt.executeUpdate(sql);
			stmt.close();

			stmt = validatedDBConn.createStatement();
			sql = "DELETE FROM " + tuple.getTable() + " WHERE rowid=" + rowIndex + ";";
			stmt.executeUpdate(sql);
			stmt.close();

			dbConn.close();
			validatedDBConn.close();
		} catch (Exception e) {
			System.err.println("In delete tuple " + e.getClass().getName() + ": " + e.getMessage());
			System.exit(0);
		}

	}

	public void updateTuple(DBTuple tuple, DBTuple newTuple) {

		if (tuple.isAnonymous()) {
			System.out.println("FATAL in set validated because tuple is anonymous");
			System.exit(0);
		}

		HashMap<String, String> columnToType = getColumnsTypes(tuple.getTable());
		int rowIndex = getRowIndex(tuple);

		try {
			Connection dbConn = null;
			Connection validatedDBConn = null;
			Statement stmt = null;
			Class.forName("org.sqlite.JDBC");
			dbConn = DriverManager.getConnection("jdbc:sqlite:" + m_dbName);
			validatedDBConn = DriverManager.getConnection("jdbc:sqlite:" + m_validatedDBName);

			stmt = dbConn.createStatement();
			String sql = "UPDATE " + tuple.getTable() + " SET ";
			int columnIndex = 0;
			for (String column : tuple.getColumns()) {
				String type = columnToType.get(column);
				if (type.toLowerCase().contains("text") || type.toLowerCase().contains("char")) {
					sql += column + "=" + "'" + newTuple.getValue(column) + "'";
				} else {
					sql += column + "=" + "" + newTuple.getValue(column);
				}

				if (columnIndex < tuple.getColumns().size() - 1) {
					sql += ", ";
				}
				columnIndex++;
			}
			sql += " WHERE rowid=" + rowIndex + ";";

			stmt.executeUpdate(sql);
			stmt.close();

			dbConn.close();
			validatedDBConn.close();
		} catch (Exception e) {
			System.err.println("In update tuple " + e.getClass().getName() + ": " + e.getMessage());
			e.printStackTrace();
			System.exit(0);
		}

		setValidated(newTuple);
	}

	public void addTuple(DBTuple tuple) {

		if (tuple.isAnonymous()) {
			System.out.println("FATAL in set validated because tuple is anonymous");
			System.exit(0);
		}

		HashMap<String, String> columnToType = getColumnsTypes(tuple.getTable());
		List<String> tableColumns = getColumns(tuple.getTable());

		try {
			Connection dbConn = null;
			Connection validatedDBConn = null;
			Statement stmt = null;
			Class.forName("org.sqlite.JDBC");
			dbConn = DriverManager.getConnection("jdbc:sqlite:" + m_dbName);
			validatedDBConn = DriverManager.getConnection("jdbc:sqlite:" + m_validatedDBName);

			stmt = dbConn.createStatement();
			String sql = "INSERT INTO " + tuple.getTable() + " (";
			String validatedDBSql = "INSERT INTO " + tuple.getTable() + " (";

			for (int i = 0; i < tableColumns.size(); i++) {
				String column = tableColumns.get(i);
				sql += column;
				validatedDBSql += column;
				if (i < tableColumns.size() - 1) {
					sql += ", ";
					validatedDBSql += ", ";
				}
			}
			sql += ") VALUES (";
			validatedDBSql += ") VALUES (";

			for (int i = 0; i < tableColumns.size(); i++) {
				String column = tableColumns.get(i);
				String type = columnToType.get(column);
				if (type.toLowerCase().contains("text") || type.toLowerCase().contains("char")) {
					sql += "'" + tuple.getValue(column) + "'";
				} else {
					sql += tuple.getValue(column);
				}

				validatedDBSql += "0";

				if (i < tableColumns.size() - 1) {
					sql += ", ";
					validatedDBSql += ", ";
				}
			}
			sql += ");";
			validatedDBSql += ");";
			stmt.executeUpdate(sql);
			stmt.close();

			stmt = validatedDBConn.createStatement();
			stmt.executeUpdate(validatedDBSql);
			stmt.close();

			dbConn.close();
			validatedDBConn.close();
		} catch (Exception e) {
			System.err.println("In update tuple " + e.getClass().getName() + ": " + e.getMessage());
			e.printStackTrace();
			System.exit(0);
		}

		setValidated(tuple);
	}

	public void updateValidatedDB() {

		boolean rhsValidatedChanged = false;
		for (Rule rule : m_rules) {
			if (!rule.isTupleGenerating()) {
				continue;
			}

			try {
				Connection dbConn = null;
				Statement stmt = null;
				Class.forName("org.sqlite.JDBC");
				dbConn = DriverManager.getConnection("jdbc:sqlite:" + m_dbName);

				stmt = dbConn.createStatement();
				String sql = rule.getTrueQuery();
				ResultSet rs = stmt.executeQuery(sql);
				while (rs.next()) {

					List<DBTuple> tuples = extractTuples(rs, rule, true);
					List<DBTuple> validatedDBTuples = extractValidatedDBTuples(tuples);
					boolean isLHSValidated = checkLHSValidated(validatedDBTuples, rule);
					if (isLHSValidated) {
						boolean temp = validateRHS(tuples.get(tuples.size() - 1),
								validatedDBTuples.get(validatedDBTuples.size() - 1), rule);
						if (temp) {
							rhsValidatedChanged = true;
						}
					}
				}
				rs.close();
				stmt.close();

				dbConn.close();
			} catch (Exception e) {
				System.err.println("In update validated DB " + e.getClass().getName() + ": " + e.getMessage());
				e.printStackTrace();
				System.exit(0);
			}
		}

		if (rhsValidatedChanged) {
			updateValidatedDB();
		}
	}

	public int getRowIndex(DBTuple tuple) {
		int rowIndex = -1;
		try {
			Connection dbConn = null;
			Statement stmt = null;
			Class.forName("org.sqlite.JDBC");
			dbConn = DriverManager.getConnection("jdbc:sqlite:" + m_dbName);

			HashMap<String, String> columnToType = new HashMap<String, String>();
			stmt = dbConn.createStatement();
			String sql = "PRAGMA table_info('" + tuple.getTable() + "');";
			ResultSet rs = stmt.executeQuery(sql);
			while (rs.next()) {
				columnToType.put(rs.getString(2), rs.getString(3));
			}
			rs.close();
			stmt.close();

			stmt = dbConn.createStatement();
			sql = "SELECT rowid FROM " + tuple.getTable() + " WHERE ";
			int columnIndex = 0;
			for (String column : tuple.getColumns()) {
				String type = columnToType.get(column);
				if (type.toLowerCase().contains("text") || type.toLowerCase().contains("char")) {
					sql += column + "=" + "'" + tuple.getValue(column) + "'";
				} else {
					sql += column + "=" + "" + tuple.getValue(column);
				}

				if (columnIndex < tuple.getColumns().size() - 1) {
					sql += " AND ";
				}
				columnIndex++;
			}
			rs = stmt.executeQuery(sql);
			if (rs.next()) {
				rowIndex = rs.getInt(1);
			}
			rs.close();
			stmt.close();

			dbConn.close();
		} catch (Exception e) {
			System.err.println("In update validated DB " + e.getClass().getName() + ": " + e.getMessage());
			e.printStackTrace();
			System.exit(0);
		}

		return rowIndex;
	}

	public List<DBTuple> extractTuples(ResultSet rs, Rule rule, boolean extractRHS) {

		List<DBTuple> result = new ArrayList<DBTuple>();
		int rsColIndex = 1;
		for (int i = 0; i < rule.getLHSFormulaCount() + rule.getRHSFormulaCount(); i++) {
			Formula formula = null;
			if (i < rule.getLHSFormulaCount()) {
				formula = rule.getLHSFormulaAt(i);
			} else {
				formula = rule.getRHSFormulaAt(i - rule.getLHSFormulaCount());
			}

			if (!extractRHS && i == rule.getLHSFormulaCount() + rule.getRHSFormulaCount() - 1) {
				continue;
			}

			if (formula instanceof ConditionalFormula) {
				continue;
			}
			RelationalFormula relFormula = (RelationalFormula) formula;
			DBTuple tuple = new DBTuple(relFormula.getTable(), false);
			for (int varIndex = 0; varIndex < relFormula.getVariableCount(); varIndex++) {
				Variable var = relFormula.getVariableAt(varIndex);
				try {
					tuple.setValue(var.getColumn(), rs.getString(rsColIndex));
				} catch (SQLException e) {
					e.printStackTrace();
				}
				rsColIndex++;
			}
			result.add(tuple);
		}

		return result;
	}

	private class EdgesTransformer implements Transformer<String, Double> {

		HashMap<String, Double> m_map;

		public EdgesTransformer(HashMap<String, Double> map) {
			m_map = map;
		}

		@Override
		public Double transform(String edgeStr) {
			return m_map.get(edgeStr);
		}

	}

	private boolean validateRHS(DBTuple tuple, DBTuple validatedDBTuple, Rule rule) {

		int currRFIndex = 0;
		for (int i = 0; i < rule.getLHSFormulaCount() + rule.getRHSFormulaCount(); i++) {
			Formula formula = null;
			if (i < rule.getLHSFormulaCount()) {
				formula = rule.getLHSFormulaAt(i);
			} else {
				formula = rule.getRHSFormulaAt(i - rule.getLHSFormulaCount());
			}

			if (formula instanceof ConditionalFormula) {
				continue;
			}
			currRFIndex++;
		}

		List<String> conditionalColumns = rule.getConditionalColumns(currRFIndex - 1);
		boolean isValidated = true;
		for (String column : conditionalColumns) {
			if (validatedDBTuple.getValue(column).equals("0")) {
				isValidated = false;
			}
		}

		if (isValidated) {
			return false;
		}

		int rowIndex = getRowIndex(tuple);
		try {
			Connection dbConn = null;
			Connection validatedDBConn = null;
			Statement stmt = null;
			Class.forName("org.sqlite.JDBC");
			dbConn = DriverManager.getConnection("jdbc:sqlite:" + m_dbName);
			validatedDBConn = DriverManager.getConnection("jdbc:sqlite:" + m_validatedDBName);

			stmt = validatedDBConn.createStatement();
			String sql = "UPDATE " + tuple.getTable() + " SET ";
			for (String column : tuple.getColumns()) {
				if (conditionalColumns.contains(column)) {
					sql += column + "=1,";
				}
			}
			if (sql.charAt(sql.length() - 1) == ',') {
				sql = sql.substring(0, sql.length() - 1);
			}
			sql += " WHERE rowid=" + rowIndex + ";";
			stmt.executeUpdate(sql);
			stmt.close();

			dbConn.close();
			validatedDBConn.close();
		} catch (Exception e) {
			System.err.println("In update validated DB " + e.getClass().getName() + ": " + e.getMessage());
			e.printStackTrace();
			System.exit(0);
		}

		return true;
	}

	private HashMap<String, String> getColumnsTypes(String tableName) {

		HashMap<String, String> columnToType = null;
		try {
			Connection dbConn = null;
			Statement stmt = null;
			Class.forName("org.sqlite.JDBC");
			dbConn = DriverManager.getConnection("jdbc:sqlite:" + m_dbName);

			columnToType = new HashMap<String, String>();
			stmt = dbConn.createStatement();
			String sql = "PRAGMA table_info('" + tableName + "');";
			ResultSet rs = stmt.executeQuery(sql);
			while (rs.next()) {
				columnToType.put(rs.getString(2), rs.getString(3));
			}
			rs.close();
			stmt.close();

			dbConn.close();
		} catch (Exception e) {
			System.err.println("In update validated DB " + e.getClass().getName() + ": " + e.getMessage());
			e.printStackTrace();
			System.exit(0);
		}

		return columnToType;
	}

	private List<String> getColumns(String tableName) {

		List<String> columns = new ArrayList<String>();
		try {
			Connection dbConn = null;
			Statement stmt = null;
			Class.forName("org.sqlite.JDBC");
			dbConn = DriverManager.getConnection("jdbc:sqlite:" + m_dbName);

			stmt = dbConn.createStatement();
			String sql = "PRAGMA table_info('" + tableName + "');";
			ResultSet rs = stmt.executeQuery(sql);
			while (rs.next()) {
				columns.add(rs.getString(2));
			}
			rs.close();
			stmt.close();

			dbConn.close();
		} catch (Exception e) {
			System.err.println("In update validated DB " + e.getClass().getName() + ": " + e.getMessage());
			e.printStackTrace();
			System.exit(0);
		}

		return columns;
	}

	private boolean checkLHSValidated(List<DBTuple> validatedDBTuples, Rule rule) {

		int relFormulaIndex = 0;
		for (int i = 0; i < rule.getLHSFormulaCount(); i++) {
			Formula formula = rule.getLHSFormulaAt(i);
			if (formula instanceof ConditionalFormula) {
				continue;
			}

			DBTuple formulaTuple = validatedDBTuples.get(relFormulaIndex);
			List<String> conditionalColumns = rule.getConditionalColumns(relFormulaIndex);
			for (String conColumn : conditionalColumns) {
				if (formulaTuple.getValue(conColumn).equals("0")) {
					return false;
				}
			}

			relFormulaIndex++;
		}

		return true;
	}

	private List<DBTuple> extractValidatedDBTuples(List<DBTuple> tuples) {
		List<DBTuple> result = new ArrayList<DBTuple>();

		for (DBTuple tuple : tuples) {

			int rowIndex = getRowIndex(tuple);
			try {
				Connection dbConn = null;
				Connection validatedDBConn = null;
				Statement stmt = null;
				Class.forName("org.sqlite.JDBC");
				dbConn = DriverManager.getConnection("jdbc:sqlite:" + m_dbName);
				validatedDBConn = DriverManager.getConnection("jdbc:sqlite:" + m_validatedDBName);

				stmt = validatedDBConn.createStatement();
				String sql = "SELECT * FROM " + tuple.getTable() + " WHERE rowid = " + rowIndex + ";";
				ResultSet rs = stmt.executeQuery(sql);
				rs.next();
				DBTuple validatedDBTuple = new DBTuple(tuple.getTable(), false);
				for (String column : tuple.getColumns()) {
					validatedDBTuple.setValue(column, rs.getString(column));
				}
				result.add(validatedDBTuple);
				rs.close();
				stmt.close();

				dbConn.close();
				validatedDBConn.close();
			} catch (Exception e) {
				System.err.println("In update validated DB " + e.getClass().getName() + ": " + e.getMessage());
				e.printStackTrace();
				System.exit(0);
			}
		}

		return result;
	}

	private void updateGraphEdges(Graph graph, DBTuple suspiciousTuple, Witness tupleWitness) {

		if (suspiciousTuple.isAnonymous()) {
			return;
		}
		
		for (int i = 0; i < tupleWitness.getTuples().size(); i++) {

			DBTuple currTuple = tupleWitness.getTuples().get(i);
			if (currTuple.equals(suspiciousTuple)) {
				continue;
			}

			if (tupleWitness.getRule().isTupleGenerating()) {
				List<List<String>> causeSubSets = getEdgeCauseColumnSetsByTGR(currTuple, suspiciousTuple, tupleWitness);
				if (causeSubSets.size() > 0) {
					graph.addEdge(currTuple, suspiciousTuple, causeSubSets);
				}
			}

			if (!tupleWitness.getRule().isTupleGenerating()) {
				List<List<String>> causeSubSets = getEdgeCauseColumnSetsByCGR(currTuple, suspiciousTuple, tupleWitness);
				if (causeSubSets.size() > 0) {
					graph.addEdge(currTuple, suspiciousTuple, causeSubSets);
				}
			}
		}
	}

	private boolean isEdgeByTGR(DBTuple srcTuple, DBTuple dstTuple, Witness tupleWitness) {

		Rule rule = tupleWitness.getRule();
		if (!rule.isTupleGenerating()) {
			System.out.println("FATAL in isEdgeByTGR because it's a CGR");
			System.exit(0);
		}

		int indexOfSrcTuple = tupleWitness.getTuples().indexOf(srcTuple);
		int indexOfDstTuple = tupleWitness.getTuples().indexOf(dstTuple);
		int witSize = tupleWitness.getTuples().size();

		if (indexOfSrcTuple == indexOfDstTuple) {

			System.out.println("FATAL in isEdgeByTGR because src tuple and dst tuple are the same");
			System.exit(0);
		}

		// If src tuple is in the RHS and dst tuple is the LHS then it's an edge
		if (indexOfSrcTuple == witSize - 1 && indexOfDstTuple < witSize - 1 && indexOfDstTuple >= 0) {
			return true;
		}

		// IF both tuples are in the LHS of the rule then it's an edge
		if (indexOfSrcTuple < witSize - 1 && indexOfSrcTuple >= 0 && indexOfDstTuple < witSize - 1
				&& indexOfDstTuple >= 0) {
			return true;
		}
		return false;
	}

	private List<List<String>> getEdgeCauseColumnSetsByTGR(DBTuple srcTuple, DBTuple dstTuple, Witness tupleWitness) {

		String warning = "";
		warning += System.lineSeparator() + "Edge Column Sets By TGR" + System.lineSeparator();

		// Check if there is an update for the dstTupe that removes this witness
		// from the srcTuple witnesses
		Rule rule = tupleWitness.getRule();
		if (!rule.isTupleGenerating()) {
			System.out.println("FATAL in getEdgeCauseColumnSetsByTGR because it's a CGR");
			System.exit(0);
		}

		Rule newNFRule = rule.toNFRule();
		warning += "The new rule: " + newNFRule.toString() + System.lineSeparator();
		warning += rule.toString() + System.lineSeparator();
		warning += "src tuple: " + srcTuple.toString() + System.lineSeparator() + "dst tuple: " + dstTuple.toString()
				+ System.lineSeparator();

		int indexOfSrcTuple = tupleWitness.getTuples().indexOf(srcTuple);
		int indexOfDstTuple = tupleWitness.getTuples().indexOf(dstTuple);

		warning += "src tuple index: " + indexOfSrcTuple + System.lineSeparator() + "dst tuple index: "
				+ indexOfDstTuple + System.lineSeparator();
		if (indexOfSrcTuple == indexOfDstTuple) {

			System.out.println("FATAL in isEdgeByCGR because src tuple and dst tuple are the same");
			System.exit(0);
		}

		List<List<String>> result = new ArrayList<List<String>>();
		List<List<List<String>>> supSetsByLen = getSupSetsByLength(new ArrayList<String>(dstTuple.getColumns()));
		warning += "Subsets by len: " + System.lineSeparator();
		for (int len = 0; len < supSetsByLen.size(); len++) {
			warning += "len = " + len + ":" + System.lineSeparator();
			for (List<String> subSet : supSetsByLen.get(len)) {
				warning += subSet.toString() + System.lineSeparator();
			}
		}
		warning += "===================================================" + System.lineSeparator();

		for (int len = 0; len < supSetsByLen.size(); len++) {
			warning += "Iterating over len = " + len + " and the subsets count = " + supSetsByLen.get(len).size()
					+ System.lineSeparator();
			for (List<String> subSet : supSetsByLen.get(len)) {
				warning += "current sub set: " + subSet.toString() + System.lineSeparator();
				// Get an assignment which assigns the values of the tuples
				// (except dstTuple)
				// variables to it's columns values including the values of of
				// dstTuple columns
				// that doesn't included in currSupSet
				HashMap<String, String> assignment = fillAssignment(newNFRule, tupleWitness, dstTuple, subSet);

				// find the new rule, assign the assignment, add conditions for
				// unchanged columns
				// and try to find an assignment for the unchanged variables
				warning += "Assignment = " + assignment.toString() + System.lineSeparator();

				Condition condition = new Condition(newNFRule);
				warning += "condition w/o assignment = " + condition.toString() + System.lineSeparator();
				condition.assign(assignment);
				warning += "condition with assignment = " + condition.toString() + System.lineSeparator();

				HashMap<String, String> columnToVar = getColumnsVars(tupleWitness, dstTuple, subSet);
				warning += "column to var: " + columnToVar.toString() + System.lineSeparator();
				HashMap<String, String> columnToType = getColumnsTypes(dstTuple.getTable());
				for (String column : subSet) {
					if (dstTuple.getValue(column).equals(".*")) {
						continue;
					}
					String operator = "!=";
					Variable colVar = new Variable(columnToVar.get(column), column, false, columnToVar.get(column),
							columnToType.get(column));
					Variable constVar = new Variable(dstTuple.getValue(column), column, true, dstTuple.getValue(column),
							columnToType.get(column));
					List<Variable> temp = new ArrayList<Variable>();
					temp.add(colVar);
					temp.add(constVar);
					ConditionalFormula conFormula = new ConditionalFormula(temp, operator);
					condition.addExternalFormula(conFormula);
				}

				warning += "condition after adding inequalities: " + condition.toString() + System.lineSeparator();

				LOGGER.warning(warning);
				warning = "";
				if (condition.hasSatisfyingAssignment()) {
					LOGGER.warning("adding subset: " + subSet.toString());
					result.add(subSet);
				}
				LOGGER.warning("------------------- FINISHED SUBSET");
			}
		}

		LOGGER.warning("Result: " + result.toString());
		LOGGER.warning(
				"===============================================+++++++++++++++++=====================================");
		return result;
	}

	private List<List<String>> getEdgeCauseColumnSetsByCGR(DBTuple srcTuple, DBTuple dstTuple, Witness tupleWitness) {

		String warning = "";
		warning += System.lineSeparator() + "Edge Column Sets By CGR" + System.lineSeparator();

		// Check if there is an update for the dstTupe that removes this witness
		// from the srcTuple witnesses
		Rule rule = tupleWitness.getRule();
		if (rule.isTupleGenerating()) {
			System.out.println("FATAL in getEdgeCauseColumnSetsByCGR because it's a TGR");
			System.exit(0);
		}

		warning += rule.toString() + System.lineSeparator();
		warning += "src tuple: " + srcTuple.toString() + System.lineSeparator() + "dst tuple: " + dstTuple.toString()
				+ System.lineSeparator();

		int indexOfSrcTuple = tupleWitness.getTuples().indexOf(srcTuple);
		int indexOfDstTuple = tupleWitness.getTuples().indexOf(dstTuple);

		warning += "src tuple index: " + indexOfSrcTuple + System.lineSeparator() + "dst tuple index: "
				+ indexOfDstTuple + System.lineSeparator();
		if (indexOfSrcTuple == indexOfDstTuple) {

			System.out.println("FATAL in isEdgeByCGR because src tuple and dst tuple are the same");
			System.exit(0);
		}

		List<List<String>> result = new ArrayList<List<String>>();
		List<List<List<String>>> supSetsByLen = getSupSetsByLength(new ArrayList<String>(dstTuple.getColumns()));
		warning += "Subsets by len: " + System.lineSeparator();
		for (int len = 0; len < supSetsByLen.size(); len++) {
			warning += "len = " + len + ":" + System.lineSeparator();
			for (List<String> subSet : supSetsByLen.get(len)) {
				warning += subSet.toString() + System.lineSeparator();
			}
		}
		warning += "===================================================" + System.lineSeparator();

		for (int len = 0; len < supSetsByLen.size(); len++) {
			warning += "Iterating over len = " + len + " and the subsets count = " + supSetsByLen.get(len).size()
					+ System.lineSeparator();
			for (List<String> subSet : supSetsByLen.get(len)) {
				warning += "current sub set: " + subSet.toString() + System.lineSeparator();
				// Get an assignment which assigns the values of the tuples
				// (except dstTuple)
				// variables to it's columns values including the values of of
				// dstTuple columns
				// that doesn't included in currSupSet
				HashMap<String, String> assignment = fillAssignment(tupleWitness.getRule(), tupleWitness, dstTuple,
						subSet);

				// find the new rule, assign the assignment, add conditions for
				// unchanged columns
				// and try to find an assignment for the unchanged variables
				warning += "Assignment = " + assignment.toString() + System.lineSeparator();

				Condition condition = new Condition(tupleWitness.getRule());
				warning += "condition w/o assignment = " + condition.toString() + System.lineSeparator();
				condition.assign(assignment);
				warning += "condition with assignment = " + condition.toString() + System.lineSeparator();

				HashMap<String, String> columnToVar = getColumnsVars(tupleWitness, dstTuple, subSet);
				warning += "column to var: " + columnToVar.toString() + System.lineSeparator();
				HashMap<String, String> columnToType = getColumnsTypes(dstTuple.getTable());
				for (String column : subSet) {
					String operator = "!=";
					Variable colVar = new Variable(columnToVar.get(column), column, false, columnToVar.get(column),
							columnToType.get(column));
					Variable constVar = new Variable(dstTuple.getValue(column), column, true, dstTuple.getValue(column),
							columnToType.get(column));
					List<Variable> temp = new ArrayList<Variable>();
					temp.add(colVar);
					temp.add(constVar);
					ConditionalFormula conFormula = new ConditionalFormula(temp, operator);
					condition.addExternalFormula(conFormula);
				}

				warning += "condition after adding inequalities: " + condition.toString() + System.lineSeparator();

				// LOGGER.warning(warning);
				warning = "";
				if (condition.hasSatisfyingAssignment()) {
					LOGGER.warning("adding subset: " + subSet.toString());
					result.add(subSet);
				}
				LOGGER.warning("------------------- FINISHED SUBSET");
			}
		}

		LOGGER.warning("Result: " + result.toString());
		LOGGER.warning(
				"===============================================+++++++++++++++++=====================================");
		return result;
	}

	private HashMap<String, String> getColumnsVars(Witness witness, DBTuple columnsOwner, List<String> columns) {

		HashMap<String, String> columnsToVars = new HashMap<String, String>();
		int relFormulaIndex = 0;
		for (int j = 0; j < witness.getRule().getLHSFormulaCount(); j++) {
			if (witness.getRule().getLHSFormulaAt(j) instanceof ConditionalFormula) {
				continue;
			}
			RelationalFormula formula = (RelationalFormula) witness.getRule().getLHSFormulaAt(j);
			DBTuple tuple = witness.getTuples().get(relFormulaIndex);
			if (tuple.equals(columnsOwner)) {
				for (int varIndex = 0; varIndex < formula.getVariableCount(); varIndex++) {
					Variable var = formula.getVariableAt(varIndex);
					if (var.isConstant()) {
						System.out.println(
								"FATAL in getColumnsVars because relational formula tuple must not be constant");
						System.exit(0);
					}

					if (columns.contains(var.getColumn())) {
						columnsToVars.put(var.getColumn(), var.getName());
					}
				}
			}
			relFormulaIndex++;
		}

		return columnsToVars;
	}

	private List<List<List<String>>> getSupSetsByLength(List<String> columns) {
		List<List<List<String>>> result = new ArrayList<List<List<String>>>();
		List<List<String>> supGroups = getSupSets(columns);

		for (int i = 0; i < columns.size() + 1; i++) {
			result.add(new ArrayList<List<String>>());
		}

		for (List<String> supGroup : supGroups) {
			result.get(supGroup.size()).add(supGroup);
		}

		return result;
	}

	private List<List<String>> getSupSets(List<String> columns) {
		List<List<String>> result = new ArrayList<List<String>>();
		if (columns.size() == 0) {
			result.add(new ArrayList<String>());
			return result;
		}

		if (columns.size() == 1) {
			result.add(new ArrayList<String>());
			List<String> oneItemSet = new ArrayList<String>();
			oneItemSet.add(columns.get(0));
			result.add(oneItemSet);
			return result;
		}

		String firstItem = columns.get(0);
		List<String> newColumns = new ArrayList<String>();
		for (int i = 1; i < columns.size(); i++) {
			newColumns.add(columns.get(i));
		}

		List<List<String>> newColumnsSupSets = getSupSets(newColumns);

		for (List<String> newColumnsSupSet : newColumnsSupSets) {
			List<String> cloneLst = new ArrayList<String>();
			for (String val : newColumnsSupSet) {
				cloneLst.add(val);
			}
			result.add(cloneLst);
		}

		for (List<String> newColumnsSupSet : newColumnsSupSets) {
			List<String> cloneLst = new ArrayList<String>();
			cloneLst.add(firstItem);
			for (String val : newColumnsSupSet) {
				cloneLst.add(val);
			}
			result.add(cloneLst);
		}

		return result;
	}

	private HashMap<String, String> fillAssignment(Rule rule, Witness witness, DBTuple unassigned,
			List<String> unassignedColumns) {
		HashMap<String, String> assignment = new HashMap<String, String>();

		List<Formula> formulas = new ArrayList<Formula>();
		for (int i = 0; i < rule.getLHSFormulaCount(); i++) {
			formulas.add(rule.getLHSFormulaAt(i));
		}
		for (int i = 0; i < rule.getRHSFormulaCount(); i++) {
			formulas.add(rule.getRHSFormulaAt(i));
		}

		int i = 0;
		for (int j = 0; j < formulas.size(); j++) {
			if (formulas.get(j) instanceof ConditionalFormula) {
				continue;
			}
			RelationalFormula formula = (RelationalFormula) formulas.get(j);
			DBTuple tuple = witness.getTuples().get(i);
			if (tuple.equals(unassigned)) {
				for (int varIndex = 0; varIndex < formula.getVariableCount(); varIndex++) {
					Variable var = formula.getVariableAt(varIndex);
					if (var.isConstant()) {
						System.out.println(
								"FATAL in fillAssignment because relational formula tuple must not be constant");
						System.exit(0);
					}
					String varName = var.getName();
					if (unassignedColumns.contains(var.getColumn())) {
						assignment.put(varName, varName);
					} else {
						String value = tuple.getValue(var.getColumn());
						assignment.put(varName, value);
					}
				}
			} else {
				for (int varIndex = 0; varIndex < formula.getVariableCount(); varIndex++) {
					String varName = formula.getVariableAt(varIndex).getName();
					String value = tuple.getValue(formula.getVariableAt(varIndex).getColumn());
					assignment.put(varName, value);
				}
			}
			i++;
		}
		return assignment;
	}

	private void buildSimpleDB() {
		m_dbName = "C:" + File.separator + "temp" + File.separator + "example.db";
		LOGGER.info("Generated the simple database " + m_dbName);
		try {
			File dbFile = new File(m_dbName);
			if (!dbFile.exists()) {
				dbFile.createNewFile();
			}
			Connection conn = null;
			Statement stmt = null;
			Class.forName("org.sqlite.JDBC");
			conn = DriverManager.getConnection("jdbc:sqlite:" + m_dbName);
			LOGGER.info("Opened simple database successfully");

			stmt = conn.createStatement();
			String sql = "DROP TABLE IF EXISTS Games;";
			stmt.executeUpdate(sql);
			stmt.close();

			stmt = conn.createStatement();
			sql = "DROP TABLE IF EXISTS Teams;";
			stmt.executeUpdate(sql);
			stmt.close();

			stmt = conn.createStatement();
			sql = "DROP TABLE IF EXISTS Countries;";
			stmt.executeUpdate(sql);
			stmt.close();

			stmt = conn.createStatement();
			sql = "DROP TABLE IF EXISTS Allowed;";
			stmt.executeUpdate(sql);
			stmt.close();

			stmt = conn.createStatement();
			sql = "CREATE TABLE Games " + "(ID INT PRIMARY KEY NOT NULL," + " first_team TEXT NOT NULL, "
					+ " second_team TEXT NOT NULL, " + " first_team_goals INTEGER NOT NULL, "
					+ " second_team_goals INTEGER NOT NULL, " + " first_team_pen INTEGER NOT NULL, "
					+ " second_team_pen INTEGER NOT NULL);";
			stmt.executeUpdate(sql);
			stmt.close();

			stmt = conn.createStatement();
			sql = "CREATE TABLE Teams " + "(ID INT PRIMARY KEY NOT NULL," + " team TEXT NOT NULL, "
					+ " country TEXT NOT NULL);";
			stmt.executeUpdate(sql);
			stmt.close();

			stmt = conn.createStatement();
			sql = "CREATE TABLE Countries " + "(ID INT PRIMARY KEY NOT NULL," + " country TEXT NOT NULL, "
					+ " continent TEXT NOT NULL);";
			stmt.executeUpdate(sql);
			stmt.close();

			stmt = conn.createStatement();
			sql = "CREATE TABLE Allowed " + "(ID INT PRIMARY KEY NOT NULL," + " team TEXT NOT NULL);";
			stmt.executeUpdate(sql);
			stmt.close();

			// Insert values to games table
			stmt = conn.createStatement();
			sql = "INSERT INTO Games (ID, first_team, second_team, first_team_goals, second_team_goals, first_team_pen, second_team_pen) VALUES "
					+ "(1,'Milan','Bayren',3,2,0,0);";
			stmt.executeUpdate(sql);
			stmt.close();

			stmt = conn.createStatement();
			sql = "INSERT INTO Games (ID, first_team, second_team, first_team_goals, second_team_goals, first_team_pen, second_team_pen) VALUES "
					+ "(2,'Italy','Bayren',3,2,0,0);";
			stmt.executeUpdate(sql);
			stmt.close();

			stmt = conn.createStatement();
			sql = "INSERT INTO Games (ID, first_team, second_team, first_team_goals, second_team_goals, first_team_pen, second_team_pen) VALUES "
					+ "(3,'Barcelona','Real',1,1,0,0);";
			stmt.executeUpdate(sql);
			stmt.close();

			stmt = conn.createStatement();
			sql = "INSERT INTO Games (ID, first_team, second_team, first_team_goals, second_team_goals, first_team_pen, second_team_pen) VALUES "
					+ "(4,'Barcelona','Bayren',3,1,0,0);";
			stmt.executeUpdate(sql);
			stmt.close();

			// Insert values to teams table
			stmt = conn.createStatement();
			sql = "INSERT INTO Teams (ID, team, country) VALUES " + "(1,'Milan','Italy');";
			stmt.executeUpdate(sql);
			stmt.close();

			stmt = conn.createStatement();
			sql = "INSERT INTO Teams (ID, team, country) VALUES " + "(2,'Bayren','Germany');";
			stmt.executeUpdate(sql);
			stmt.close();

			stmt = conn.createStatement();
			sql = "INSERT INTO Teams (ID, team, country) VALUES " + "(3,'Barcelona','Spain');";
			stmt.executeUpdate(sql);
			stmt.close();

			stmt = conn.createStatement();
			sql = "INSERT INTO Teams (ID, team, country) VALUES " + "(4,'Italy','Italy');";
			stmt.executeUpdate(sql);
			stmt.close();

			stmt = conn.createStatement();
			sql = "INSERT INTO Teams (ID, team, country) VALUES " + "(5,'Real','Spain');";
			stmt.executeUpdate(sql);
			stmt.close();

			// Insert values to countries table
			stmt = conn.createStatement();
			sql = "INSERT INTO Countries (ID, country, continent) VALUES " + "(1,'Italy','Asia');";
			stmt.executeUpdate(sql);
			stmt.close();

			stmt = conn.createStatement();
			sql = "INSERT INTO Countries (ID, country, continent) VALUES " + "(2,'Italy','Europe');";
			stmt.executeUpdate(sql);
			stmt.close();

			stmt = conn.createStatement();
			sql = "INSERT INTO Countries (ID, country, continent) VALUES " + "(3,'Germany','Europe');";
			stmt.executeUpdate(sql);
			stmt.close();

			// Insert values to allowed table
			stmt = conn.createStatement();
			sql = "INSERT INTO Allowed (ID, team) VALUES " + "(1,'Barcelona');";
			stmt.executeUpdate(sql);
			stmt.close();

			stmt = conn.createStatement();
			sql = "INSERT INTO Allowed (ID, team) VALUES " + "(2,'Real');";
			stmt.executeUpdate(sql);
			stmt.close();

			stmt = conn.createStatement();
			sql = "INSERT INTO Allowed (ID, team) VALUES " + "(3,'Bayren');";
			stmt.executeUpdate(sql);
			stmt.close();

			stmt = conn.createStatement();
			sql = "INSERT INTO Allowed (ID, team) VALUES " + "(4,'Milan');";
			stmt.executeUpdate(sql);

			stmt.close();
			conn.close();
		} catch (Exception e) {
			System.err.println(e.getClass().getName() + ": " + e.getMessage());
			System.exit(0);
		}
		LOGGER.info("Simple database created successfully");
	}

	private void buildValidatedDB() {

		m_validatedDBName = new String(m_dbName);
		if (m_validatedDBName.indexOf(".") > 0) {
			m_validatedDBName = m_validatedDBName.substring(0, m_validatedDBName.lastIndexOf("."));
		}
		m_validatedDBName = m_validatedDBName + "_validated.db";

		LOGGER.info("Generated the validated database " + m_validatedDBName);
		try {
			File dbFile = new File(m_validatedDBName);
			if (dbFile.exists()) {
				dbFile.delete();
			}
			dbFile.createNewFile();

			Connection dbConn = null;
			Connection validatedDBConn = null;
			Statement stmt = null;
			Class.forName("org.sqlite.JDBC");
			dbConn = DriverManager.getConnection("jdbc:sqlite:" + m_dbName);
			validatedDBConn = DriverManager.getConnection("jdbc:sqlite:" + m_validatedDBName);
			LOGGER.info("Opened validated database successfully");

			List<String> tables = new ArrayList<String>();
			int tableID = 0;
			stmt = dbConn.createStatement();
			ResultSet rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table';");
			while (rs.next()) {
				String tableName = rs.getString(1);
				tables.add(tableName);
				m_tableToID.put(tableName, new Integer(tableID));
				tableID++;
			}
			rs.close();
			stmt.close();

			for (String table : tables) {
				List<String> columns = new ArrayList<String>();
				stmt = dbConn.createStatement();
				rs = stmt.executeQuery("PRAGMA table_info('" + table + "');");
				while (rs.next()) {
					columns.add(rs.getString(2));
				}
				stmt.close();

				long rowsCount = 0;
				stmt = dbConn.createStatement();
				rs = stmt.executeQuery("SELECT COUNT(*) FROM '" + table + "';");
				rs.next();
				rowsCount = rs.getLong(1);
				rs.close();
				stmt.close();

				// Create the table
				String sql = "CREATE TABLE " + table + " ";
				String columnsStr = "(";
				for (int i = 0; i < columns.size(); i++) {
					String column = columns.get(i);
					if (i == columns.size() - 1) {
						columnsStr += column + " INT NOT NULL";
					} else {
						columnsStr += column + " INT NOT NULL, ";
					}
				}
				columnsStr += ")";
				sql += columnsStr + ";";
				stmt = validatedDBConn.createStatement();
				stmt.executeUpdate(sql);
				stmt.close();

				for (long rowIndex = 0; rowIndex < rowsCount; rowIndex++) {
					// For each table row insert zeroes row
					columnsStr = "(";
					for (int i = 0; i < columns.size(); i++) {
						String column = columns.get(i);
						if (i == columns.size() - 1) {
							columnsStr += column + "";
						} else {
							columnsStr += column + ", ";
						}
					}
					columnsStr += ")";

					sql = "INSERT INTO " + table + " " + columnsStr + " VALUES ";
					String valuesStr = "(";
					for (int i = 0; i < columns.size(); i++) {
						if (i == columns.size() - 1) {
							valuesStr += "0";
						} else {
							valuesStr += "0, ";
						}
					}
					valuesStr += ")";
					sql += valuesStr + ";";
					stmt = validatedDBConn.createStatement();
					stmt.executeUpdate(sql);
					stmt.close();
				}

			}

			dbConn.close();
			validatedDBConn.close();
		} catch (Exception e) {
			System.err.println(e.getClass().getName() + ": " + e.getMessage());
			System.exit(0);
		}
		LOGGER.info("Validated database created successfully");
	}
}