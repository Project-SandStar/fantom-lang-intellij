using concurrent

** A small web page reader. See <doctag>`fetch`</doctag> and <doctag>`defaultTimeout`</doctag>.
**
** Reads one page and prints its size:
**
**   Fetcher().fetch(`http://fantom.org/`)
<facet>@Js</facet> <facet>@Serializable</facet> { <facetval>simple</facetval> = true }
const class <decl>Fetcher</decl> : <type>Reader</type>
{
  ** Default wait time for one request.
  const static <utype>Duration</utype> <const>defaultTimeout</const> := 5sec

  ** Upper limit for the body size.
  const <utype>Int</utype> <const>maxSize</const> := 1024 * 1024

  ** Pages read by every instance.
  static <utype>Int</utype> <static>hits</static> := 0

  ** Pages read by this instance.
  <utype>Int</utype> <field>count</field> := 0

  ** Known content types.
  static <utype>Str</utype>[] <scall>types</scall>() { ["text/html", "application/json"] }

  ** Read one page and return the body, or null when it is too large.
  <utype>Str</utype>? <method>fetch</method>(<utype>Uri</utype> <param>uri</param>, <utype>Duration</utype>? <param>timeout</param> := null)
  {
    wait := <param>timeout</param> ?: <const>defaultTimeout</const>
    echo("Fetching <interp>$uri</interp> in <interp>${wait.toMillis}</interp>ms<esc>\n</esc>")
    if (<param>uri</param>.<ucall>scheme</ucall> != "http") throw ArgErr("Not a web uri: <interp>$uri</interp>")
    body := <call>read</call>(<param>uri</param>, <local>wait</local>)
    <ifield>count</ifield>++; <static>hits</static>++
    lines := <local>body</local>.<ucall>splitLines</ucall>.<ucall>findAll</ucall> <pipe>|</pipe><utype>Str</utype> line-><utype>Bool</utype><pipe>|</pipe> { <param>line</param>.<ucall>size</ucall> > 0 }
    <local>lines</local>.<ucall>each</ucall> <pipe>|</pipe>line, i<pipe>|</pipe> { echo("<interp>$i</interp>: <interp>$line</interp>") }
    return <local>body</local>.<ucall>size</ucall> > <const>maxSize</const> ? null : <local>body</local>
  }

  // A char literal, a dsl and a type literal.
  private <utype>Str</utype> <method>read</method>(<utype>Uri</utype> <param>uri</param>, <utype>Duration</utype> <param>wait</param>)
  {
    sep := 'x'
    re  := Regex<|\d+|>
    t   := <type>Fetcher</type>#
    return <param>uri</param>.<ucall>get</ucall>.<ucall>readAllStr</ucall>
  }

  ** Entry point: `fan fetcher http://fantom.org/`
  static <utype>Int</utype> <scall>main</scall>(<utype>Str</utype>[] <param>args</param>)
  {
    f := <type>Fetcher</type>()
    <param>args</param>.<ucall>each</ucall> <pipe>|</pipe>arg<pipe>|</pipe> { <local>f</local>.<call>fetch</call>(<param>arg</param>.<ucall>toUri</ucall>) }
    return <local>f</local>.<ifield>count</ifield>
  }
}

** The direction a page scrolls.
<kw>enum</kw> class <decl>Dir</decl> { <const>up</const>, <const>down</const> }
