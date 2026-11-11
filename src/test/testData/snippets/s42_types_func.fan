class A {
  |OutStream out, Obj obj| writer
  |InStream in->Obj| reader
  |DocErr| onErr
  |Link link|? onLink := null
  internal const |HtmlContext->NodeRenderer|[] nodeRendererFactories
  |Node node, Str msg|? onWarnCb { protected set }
  |Expr expr->Expr| func
  |->Int?| firstChar(Context ctx) { null }
  Void g(|Int->Str| a, |->| b := null, |This|? c := null, |Obj?->Obj?| d, |a, b| e, |->Int?| h) {}
}
