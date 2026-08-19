object Repro {
  def f(): Unit = {
    List(1).map(_ + 1)
    ()
  }
}
