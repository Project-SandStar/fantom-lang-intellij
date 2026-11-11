class Foo
{
  Int x := 1 { get { &x } private set { &x = it } }
  OutStream? out { private set }
  override Int p2 := 103 { get { return &p2 } set { &p2 = it } }
  Int lastChar := 0 { private set }
  Int y { set { &y = it } override get { return &y } }

  override Paint? paint := null // set in make
  {
    set
    {
      if (&paint == it) return
      &paint = it
    }
  }

  Int z
  {
    get;
    protected set;
  }
}
