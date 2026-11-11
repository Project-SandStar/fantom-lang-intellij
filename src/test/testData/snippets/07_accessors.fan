class Foo
{
  override SourceLine? line := null { private set }
  Int lastChar := 0 { private set }
  Int x := 1 { get { &x } set { &x = it } }
  |Link link|? onLink := null
  |Node node, Str msg|? onWarnCb { protected set }
}
