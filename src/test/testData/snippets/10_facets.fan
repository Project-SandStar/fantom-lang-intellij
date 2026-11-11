class Foo
{
  @Opt { help = "username to use"; aliases=["u"] }
  Str? user
  @NoDoc protected new make(Future wrap) {}
  @Arg { help="Verbose" }
  Bool verbose
}
