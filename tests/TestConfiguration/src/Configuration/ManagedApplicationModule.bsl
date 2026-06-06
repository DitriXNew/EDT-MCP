
Procedure BeforeStart(Cancel)
	//TODO: Insert the handler content
EndProcedure
// TEST DEBUG
Procedure OnStart()
	Greeting = "Debug e2e OK";
	Sum = 40 + 2;
	Total = Sum * 10;
	Message(Greeting + " | sum=" + Sum + " | total=" + Total);
EndProcedure
