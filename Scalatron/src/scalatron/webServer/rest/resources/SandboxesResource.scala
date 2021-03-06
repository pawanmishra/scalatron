package scalatron.webServer.rest.resources

import javax.ws.rs._
import core.{Response, MediaType}
import scalatron.core.Scalatron.SandboxState
import scala.collection.convert.Wrappers._
import collection.JavaConverters
import scalatron.webServer.rest.UserSession
import UserSession.SandboxAttributeKey
import org.eclipse.jetty.http.HttpStatus
import scalatron.core.Scalatron
import scalatron.webServer.rest.resources.SandboxesResource.CommandParser


@Produces(Array(MediaType.APPLICATION_JSON))
@Consumes(Array(MediaType.APPLICATION_JSON))
@Path("users/{user}/sandboxes")
class SandboxesResource extends ResourceWithUser {
    @POST
    def create(startConfig: SandboxesResource.StartConfig) = {
        if(!userSession.isLoggedOnAsUserOrAdministrator(userName)) {
            Response.status(CustomStatusType(HttpStatus.UNAUTHORIZED_401, "must be logged on as '" + userName + "' or '" + Scalatron.Constants.AdminUserName + "'")).build()
        } else {
            try {
                scalatron.user(userName) match {
                    case Some(user) =>
                        userSession -= SandboxAttributeKey

                        // extract the arguments, like Map("-x" -> 50, "-y" -> 50)
                        val argMap = JMapWrapper(startConfig.getConfig).toMap
                        val sandbox = user.createSandbox(argMap)
                        val state = sandbox.initialState
                        userSession += SandboxAttributeKey -> state

                        Response.ok(SandboxesResource.createSandboxResult(userName, state)).build()

                    case None =>
                        Response.status(CustomStatusType(HttpStatus.NOT_FOUND_404, "user '" + userName + "' does not exist")).build()
                }
            } catch {
                case e: IllegalStateException =>
                    // source directory does not exist
                    Response.status(CustomStatusType(HttpStatus.INTERNAL_SERVER_ERROR_500, e.getMessage)).build()
            }
        }
    }

    @DELETE
    def deleteAll() {
        if(!userSession.isLoggedOnAsUserOrAdministrator(userName)) {
            Response.status(CustomStatusType(HttpStatus.UNAUTHORIZED_401, "must be logged on as '" + userName + "' or '" + Scalatron.Constants.AdminUserName + "'")).build()
        } else {
            scalatron.user(userName) match {
                case Some(user) =>
                    userSession -= SandboxAttributeKey
                    Response.noContent().build()

                case None =>
                    Response.status(CustomStatusType(HttpStatus.NOT_FOUND_404, "user '" + userName + "' does not exist")).build()
            }
        }
    }

    @DELETE
    @Path("{id}")
    def deleteSingle() {
        if(!userSession.isLoggedOnAsUserOrAdministrator(userName)) {
            Response.status(CustomStatusType(HttpStatus.UNAUTHORIZED_401, "must be logged on as '" + userName + "' or '" + Scalatron.Constants.AdminUserName + "'")).build()
        } else {
            scalatron.user(userName) match {
                case Some(user) =>
                    userSession -= SandboxAttributeKey
                    Response.noContent().build()

                case None =>
                    Response.status(CustomStatusType(HttpStatus.NOT_FOUND_404, "user '" + userName + "' does not exist")).build()
            }
        }
    }

    @GET
    @Path("{id}/{time}")
    def getForIdAndTime(@PathParam("id") id: Int, @PathParam("time") time: Int): Response = {
        if(!userSession.isLoggedOnAsUserOrAdministrator(userName)) {
            Response.status(CustomStatusType(HttpStatus.UNAUTHORIZED_401, "must be logged on as '" + userName + "' or '" + Scalatron.Constants.AdminUserName + "'")).build()
        } else {
            scalatron.user(userName) match {
                case Some(user) =>
                    userSession.get(SandboxAttributeKey) match {
                        case None =>
                            // Ok no sandbox found - client must create one.
                            Response.status(CustomStatusType(HttpStatus.NOT_FOUND_404, "sandbox id=%d at time=%d for user '%s' does not exist".format(id, time, userName))).build()

                        case Some(currentSandboxState: SandboxState) =>
                            val currentId = currentSandboxState.sandbox.id
                            if(currentId != id) {
                                Response.status(CustomStatusType(HttpStatus.NOT_FOUND_404, "sandbox with id=%d does not exist for user '%s'".format(id, userName))).build()
                            } else {
                                val currentTime = currentSandboxState.time
                                val timeDelta = time - currentTime
                                if(timeDelta == 0) {
                                    Response.ok(SandboxesResource.createSandboxResult(userName, currentSandboxState)).build()
                                } else
                                if(timeDelta < 0) {
                                    Response.status(CustomStatusType(HttpStatus.NOT_FOUND_404, "cannot step sandbox with id=%d for user '%s' backwards in time".format(id, userName, timeDelta))).build()
                                } else {
                                    val updatedSandboxState = currentSandboxState.step(timeDelta)
                                    userSession += SandboxAttributeKey -> updatedSandboxState
                                    Response.ok(SandboxesResource.createSandboxResult(userName, updatedSandboxState)).build()
                                }
                            }
                    }

                case None =>
                    Response.status(CustomStatusType(HttpStatus.NOT_FOUND_404, "user '" + userName + "' does not exist")).build()
            }
        }
    }


    @PUT
    def next(step: Steps): Response = {
        requireLoggedInAsOwningUserOrAdministrator()

        userSession.get(SandboxAttributeKey) match {
            case None =>
                // Ok no sandbox found - client must create one.
                Response.noContent().build();

            case Some(sandbox: SandboxState) =>
                val sandboxState = sandbox.step(step.getSteps)
                val entities = sandboxState.entities

                userSession += SandboxAttributeKey -> sandboxState

                val mappedEntities = entities.map(e => {
                    val inputString = e.mostRecentControlFunctionInput
                    val (opcode, params) =
                        if(inputString.isEmpty) {
                            ("", Map.empty[String, String])
                        } else {
                            CommandParser.splitCommandIntoOpcodeAndParameters(inputString)
                        }

                    SandboxesResource.EntityDto(
                        e.id,
                        e.name,
                        e.isMaster,
                        SandboxesResource.InputCommand(opcode, JavaConverters.mapAsJavaMap(params)),
                        SandboxesResource.extractOutput(e.mostRecentControlFunctionOutput),
                        e.debugOutput)
                }).toArray

                Response.ok(new SandboxResult(mappedEntities)).build();
        }
    }
}

object SandboxesResource {
    /** Utility methods for parsing strings containing a single command of the format
      *  "Command(key=value,key=value,...)"
      *  Note: this is a duplicate of the CommandParser in BotWar - maybe eliminate this some day.
      */
    object CommandParser {
        /** "Command(..)" => ("Command", Map( ("key" -> "value"), ("key" -> "value"), ..}) */
        def splitCommandIntoOpcodeAndParameters(command: String): (String, Map[String, String]) = {
            val segments = command.split('(')
            if( segments.length != 2 )
                throw new IllegalStateException("invalid command: \"" + command + "\"")
            val opcode = segments(0)
            val params = segments(1).dropRight(1).split(',')
            val keyValuePairs = params.map(splitParameterIntoKeyValue).toMap
            (opcode, keyValuePairs)
        }

        /** "key=value" => ("key","value") */
        def splitParameterIntoKeyValue(param: String): (String, String) = {
            val segments = param.split('=')
            (segments(0), if( segments.length >= 2 ) segments(1) else "")
        }
    }



    private def createSandboxResult(userName: String, state: Scalatron.SandboxState) : SandboxesResource.SandboxCreationResult = {
        val sandboxId = state.sandbox.id
        val sandboxTime = state.time

        val timePlus0 = "/api/users/%s/sandboxes/%d/%d".format(userName, sandboxId, sandboxTime)
        val timePlus1 = "/api/users/%s/sandboxes/%d/%d".format(userName, sandboxId, sandboxTime+1)
        val timePlus2 = "/api/users/%s/sandboxes/%d/%d".format(userName, sandboxId, sandboxTime+2)
        val timePlus10 = "/api/users/%s/sandboxes/%d/%d".format(userName, sandboxId, sandboxTime+10)

        val mappedEntities = state.entities.map(e => {
            val inputString = e.mostRecentControlFunctionInput
            val (opcode, params) =
                if(inputString.isEmpty) {
                    ("", Map.empty[String, String])
                } else {
                    CommandParser.splitCommandIntoOpcodeAndParameters(inputString)
                }

            SandboxesResource.EntityDto(
                e.id,
                e.name,
                e.isMaster,
                SandboxesResource.InputCommand(opcode, JavaConverters.mapAsJavaMap(params)),
                extractOutput(e.mostRecentControlFunctionOutput),
                e.debugOutput)
        }).toArray

        SandboxesResource.SandboxCreationResult(
            sandboxId,
            timePlus0, timePlus1, timePlus2, timePlus10,
            state.time,
            mappedEntities
        )
    }

    private def extractOutput(in: Iterable[(String, Iterable[(String, String)])]): Array[SandboxesResource.InputCommand] =
        in.map(e => {
            val op = e._1
            val params = e._2
            SandboxesResource.InputCommand(op, JavaConverters.mapAsJavaMap(params.toMap))
        }).toArray



    // incoming to PUT
    case class StartConfig(var config: java.util.HashMap[String, String]) {
        def this() = this(null)
        def getConfig = config
        def setConfig(config: java.util.HashMap[String, String]) { this.config = config }
    }

    // outgoing from PUT
    case class SandboxCreationResult(
        id: Int,
        timePlus0: String, timePlus1: String, timePlus2: String, timePlus10: String,
        time: Int,
        entities: Array[EntityDto]
    )
    {
        def getId = id

        def getUrl = timePlus0
        def getUrlPlus1 = timePlus1
        def getUrlPlus2 = timePlus2
        def getUrlPlus10 = timePlus10

        def getTime = time

        def getEntities = entities
    }

    case class EntityDto(id: Int, name: String, master: Boolean, input: InputCommand, output: Array[InputCommand], debugOut: String) {
        def getId = id
        def getDebugOutput = debugOut
        def getName = name
        def isMaster = master
        def getOutput = output
        def getInput = input
    }

    case class InputCommand(oc: String, p: java.util.Map[String, String]) {
        def getOpcode = oc
        def getParams = p
    }
}



case class SandboxResult(entities: Array[SandboxesResource.EntityDto]) {
    def getEntities = entities
}


class Steps(var i: Int) {
    def this() = this(0)
    def getSteps = i
    def setSteps(s: Int) { i = s }
}