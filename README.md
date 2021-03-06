<h1>Elda, an implementation of the Linked Data API</h1>

<p>
Elda is a Java implementation of the 
<a href="http://code.google.com/p/linked-data-api/" rel="nofollow">Linked Data API</a>,
which provides a configurable way to access RDF data using simple 
RESTful URLs that are translated into queries to a SPARQL endpoint. 
The API developer (probably you) writes an API spec (in RDF) which 
specifies how to translate URLs into queries. 
</p>

<p>
Elda is the Epimorphics implementation of the LDA. It comes with some pre-built  examples 
which allow you to experiment with the style of query and get started with building your own configurations. 
</p>

<p>
See the <a href="http://epimorphics.github.io/elda/docs/E1.2.29/index.html">quickstart documentation</a>.
For summaries of the latest releases, see the
<a href="http://epimorphics.github.io/elda/">documentation root</a>.
</p>

<p>
	<b>WARNING</b> There is an item-counting problem in the latest
	Elda (1.2.30) and possibly in 1.2.29 (which introduced item
	counting). Do NOT start using these releases until this note
	is removed and a new Elda release is officially announced.
</p>

