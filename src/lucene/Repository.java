package lucene;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import init.DataSet;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.queryparser.classic.QueryParserBase;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
//import org.apache.lucene.search.TopScoreDocCollector;
//import org.apache.lucene.analysis.TokenStream;
//import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
//import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;

import tokenization.DocParser;

public class Repository {
	private Directory index;
	private StandardAnalyzer analyzer;
	private IndexSearcher searcher;
	private IndexWriter w;
	private List<DocParser> result = new LinkedList<DocParser>();
	private List<DocParser> temp = new LinkedList<DocParser>();
	private int hitsPerPage = 10; // Numero massimo di cv che vengono restituiti
									// dalla search
	private final String ENTITY = "entity"; // nome dei campi
	private final String DBPEDIA = "dbpedia";
	private final String CV = "cv";
	private final int ENTITY_ON_ENTITY = 5; // valore peso
	private final int ENTITY_ON_DBPEDIA = 2;

	public Repository() {
		//creazione directory di Lucene
		analyzer = new StandardAnalyzer();
		try {
			File f = new File("myLucene");
			if (!f.exists()) {
				index = FSDirectory.open(Paths.get("myLucene"));
				DataSet ds = new DataSet();
				List<DocParser> list_default_doc = ds.getList();
				addDocsParser(list_default_doc);
			}

			else
				index = FSDirectory.open(Paths.get("myLucene"));
		} catch (IOException e) {
			System.out.println("Errore apertura file myLucene");
		}
	}
	
	//metodo di supporto per inserimento del data set
	public void addDocsParser(List<DocParser> list) {
		Iterator<DocParser> it = list.iterator();
		while (it.hasNext()) {
			addDocParser(it.next());
		}
	}

	// aggiungi un cv a Lucene
	public void addDocParser(DocParser dp) {
		try {
			IndexWriterConfig config = new IndexWriterConfig(analyzer);
			w = new IndexWriter(index, config);

			Document doc = new Document();
			// inserisco il testo completo del cv
			doc.add(new TextField(CV, dp.getText(), Field.Store.YES));
			// inserisco i singoli tag
			Iterator<String> it = dp.getEntity().iterator();
			while (it.hasNext()) {
				String tag = it.next();
				if (tag != null)
					doc.add(new TextField(ENTITY, tag, Field.Store.YES));
			}
			// inserisco i singoli tag delle categorie di dbpedia dei tag
			Iterator<String> it2 = dp.getDbpedia().iterator();
			while (it2.hasNext()) {
				String tag = it2.next();
				if (tag != null)
					doc.add(new TextField(DBPEDIA, tag, Field.Store.YES));
			}
			// aggiungo doc al file
			w.addDocument(doc);
			w.close();
		} catch (IOException e) {
			System.out.println("Errore writer");
		}

	}

	// cerca un cv su Lucene e restituisce una lista di doc che corrispondono ai
	// parametri
	public List<DocParser> search(DocParser dp) throws ParseException, IOException {
		new QueryParser(ENTITY, analyzer);
		// Trasformo lista di tag in due stringhe: una per i tag principali e
		// uno per gli altri
		String querystr1 = QueryParserBase.escape(dp.getEntity().toString());
		String temp = FromTagToString(querystr1, dp.getDbpedia());
		String querystr2 = QueryParserBase.escape(temp);
		try {
			// parso le query, sia per le entity che per le dbpedia, per
			// entrambe le query
			Query queryEntityOnEntity = new QueryParser(ENTITY, analyzer).parse(querystr1);
			Query queryEntityOnDbpedia = new QueryParser(DBPEDIA, analyzer).parse(querystr1);
			Query queryDbpediaOnEntity = new QueryParser(ENTITY, analyzer).parse(querystr2);
			Query queryDbpediaOnDbpedia = new QueryParser(DBPEDIA, analyzer).parse(querystr2);
			// invio la ricerca delle singole query a Lucene e inserisco i
			// risultati nella lista result
			searchLucene(queryEntityOnEntity, dp);
			searchLucene(queryEntityOnDbpedia, dp);
			searchLucene(queryDbpediaOnEntity, dp);
			searchLucene(queryDbpediaOnDbpedia, dp);
		} catch (ParseException e) {

			System.out.println(e.getMessage());
		}
		ordinaResult();
		return result;
	}

	public String FromTagToString(String Startquery, List<String> tags) {
		String query = Startquery;
		Iterator<String> it = tags.iterator();
		while (it.hasNext()) {
			String tag = it.next();
			query = query + " " + tag;
		}
		return query;
	}

	//metodo per cercare nel file di Lucene
	public void searchLucene(Query query, DocParser dp) throws IOException {
		try {
			// apro l'indice di lettura del file
			IndexReader reader = DirectoryReader.open(index);
			searcher = new IndexSearcher(reader);

			// Fa la ricerca e restituisce un numero max di doc che matchano con
			// la query
			TopDocs docs = searcher.search(query, hitsPerPage);

			// trasformo la lista in un vettore di doc
			ScoreDoc[] hits = docs.scoreDocs;

			// Ricavo da ogni documento che matcha con la query
			for (int i = 0; i < hits.length; i++) {
				int docId = hits[i].doc;
				
				Document doc = searcher.doc(docId);

				if (isOnTemp(docId))
					return;
				else
					addInTemp(doc, docId, dp);
			}
		} catch (IOException e) {
			System.out.println(e.getMessage());
		}

	}

	//inserimento in una lista temporanea dei documenti ritornati dalla ricerca
	public void addInTemp(Document doc, int docId, DocParser query) {
		// prelevo tutti i campi salvati di doc
		int weight = 0;
		String text = doc.get(CV);
		String[] tags_entity = doc.getValues(ENTITY);
		List<String> tags_entity_list = FromVectorToList(tags_entity);
		String[] tags_dbpedia = doc.getValues(DBPEDIA);
		List<String> tags_dbpedia_list = FromVectorToList(tags_dbpedia);

		// creo un docParser equivalente al doc
		DocParser dp = new DocParser();
		dp.setId(docId);
		dp.setText(text);
		dp.setDbpedia(tags_dbpedia_list);
		dp.setEntity(tags_entity_list);

		// trovo i tag matchati
		List<String> tags_match = new LinkedList<String>();
		(query.getEntity()).addAll(query.getDbpedia());
		
		int counter_tag_match_entity = 0;
		int counter_tag_match_dbpedia = 0;
		Iterator<String> itEnt = tags_entity_list.iterator();
		Iterator<String> itDb = tags_entity_list.iterator();
		Iterator<String> it = query.getEntity().iterator();
		while (it.hasNext()) {
			String tag = it.next();
			while (itEnt.hasNext()) {
				if (compare(tag, itEnt.next())) {
					tags_match.add(tag);
					counter_tag_match_entity++;
				}
			}
			while (itDb.hasNext()) {
				if (compare(tag, itDb.next())) {
					tags_match.add(tag);
					counter_tag_match_dbpedia++;
				}

			}
		}
		//assegno un peso ai documenti in base ai match trovati
		weight = (counter_tag_match_entity * ENTITY_ON_ENTITY) + (counter_tag_match_dbpedia * ENTITY_ON_DBPEDIA);
		// setto i tag matchati
		dp.setMatchedTags(tags_match);
		// setto i pesi di ogni doc presente
		dp.setWeight(weight);
		// inserisco docParser
		temp.add(dp);
	}

	// metodo ausiliario per confrontare se due stringhe sono simili
	private boolean compare(String tag1, String tag2) {
		boolean result = ((tag1.toLowerCase().contains(tag2.toLowerCase()))
				|| (tag2.toLowerCase().contains(tag1.toLowerCase())));
		return result;
	}

	// metodo ausiliario che trasforma un array di tag in una lista di tag
	private List<String> FromVectorToList(String[] tags) {
		List<String> tags_list = new LinkedList<String>();
		for (int i = 0; i < tags.length; i++) {
			tags_list.add(tags[i]);
		}
		return tags_list;
	}

	// verifico se un doc è già presente nella lista temp
	public boolean isOnTemp(int docId) {
		Iterator<DocParser> it = temp.iterator();
		while (it.hasNext()) {
			DocParser dp = it.next();
			if (dp.getId() == docId)
				return true;
		}
		return false;
	}
	
	// sposta i doc da temp in result, ordinandoli per peso
	public void ordinaResult(){
		while(!temp.isEmpty()){
			DocParser dc=maxDocParserInTemp();
			result.add(dc);
			temp.remove(dc);
		}
		
	}

	// metodo ausiliario che trova il DocParser di peso maggiore in una lista
	private DocParser maxDocParserInTemp() {
		Iterator<DocParser> it= temp.iterator();
		DocParser max= it.next();
		while (it.hasNext()){
			DocParser doc= it.next();
			if(max.getWeight()<doc.getWeight())
				max=doc;		
		}
		return max;
	}

}
