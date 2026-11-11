class Foo
{
  private [Str:Obj][] queued := [Str:Obj][,]
  [Str:Str]? extraResHeaders
  [Str:Str] attrs(Node node, Str tagName, [Str:Str] defAttrs := newAttrs) { defAttrs }
  internal const |HtmlContext->NodeRenderer|[] factories
  Void f()
  {
    rules := ([Str:Obj][])tz->rules
    [Str:Int]? x := null
    y := jsonRes["pods"] ?: throw Err("x")
    [u1, u2].each |Uri u| { echo(u) }
    verifySer("x", [Str:Int?][]#)
  }
}
