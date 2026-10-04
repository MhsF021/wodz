package br.com.wodz.common;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

import static java.util.Map.entry;

/**
 * Converte erros em ProblemDetail (RFC 9457). As regras que vivem no banco
 * (triggers/procedures com RAISE EXCEPTION) chegam aqui como SQLSTATE P0001
 * e viram 422 com a mensagem escrita na própria trigger.
 */
@RestControllerAdvice
// Antes do ProblemDetailsExceptionHandler do Spring Boot (spring.mvc.problemdetails),
// que respondia "Invalid request content." às falhas de @Valid, sem os erros por campo.
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final Map<String, String> CONSTRAINT_MESSAGES = Map.ofEntries(
            entry("ux_app_user_email", "Este e-mail já tem cadastro"),
            entry("ux_app_user_google_sub", "Esta conta Google já está vinculada a outro usuário"),
            entry("uq_championship_slug", "Já existe um campeonato com esse endereço"),
            entry("uq_category_name", "Já existe uma categoria com esse nome neste campeonato"),
            entry("uq_workout_number", "Já existe uma prova com esse número nesta categoria"),
            entry("ux_team_name_per_category", "Já existe uma equipe com esse nome nesta categoria"),
            entry("uq_team_member_cpf", "O mesmo CPF aparece duas vezes na equipe"),
            entry("uq_heat_number", "Já existe uma bateria com esse número nesta prova"),
            entry("uq_heat_lane", "Duas equipes foram colocadas na mesma raia"),
            entry("uq_heat_team", "A equipe aparece duas vezes na mesma bateria"),
            entry("uq_score_team_workout", "Esta equipe já tem resultado nesta prova"),
            entry("ux_payment_one_approved", "Esta inscrição já tem um pagamento aprovado"),
            entry("ck_heat_lane_max", "Cada bateria tem no máximo 8 equipes"));

    @ExceptionHandler(ApiException.class)
    ProblemDetail handleApi(ApiException ex) {
        return problem(ex.getStatus(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(fe -> errors.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "Alguns campos precisam de correção");
        pd.setProperty("errors", errors);
        return pd;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handleUnreadable(HttpMessageNotReadableException ex) {
        return problem(HttpStatus.BAD_REQUEST, "O corpo da requisição está em formato inválido");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN, "Seu perfil não tem acesso a esta área");
    }

    @ExceptionHandler({DataAccessException.class, TransactionSystemException.class})
    ProblemDetail handleDatabase(RuntimeException ex) {
        PSQLException pg = findCause(ex, PSQLException.class);
        if (pg != null && pg.getServerErrorMessage() != null) {
            ServerErrorMessage server = pg.getServerErrorMessage();
            String state = pg.getSQLState();
            String constraint = server.getConstraint();
            switch (state) {
                case "P0001":
                    return problem(HttpStatus.UNPROCESSABLE_ENTITY, server.getMessage());
                case "P0002":
                    return problem(HttpStatus.NOT_FOUND, server.getMessage());
                case "23505":
                    return problem(HttpStatus.CONFLICT,
                            CONSTRAINT_MESSAGES.getOrDefault(constraint, "Já existe um registro com esses dados"));
                case "23503":
                    return problem(HttpStatus.CONFLICT, "Este registro está em uso por outros dados e não pode ser removido");
                case "23514":
                    return problem(HttpStatus.BAD_REQUEST, CONSTRAINT_MESSAGES.getOrDefault(constraint,
                            "Valor fora das regras permitidas (" + constraint + ")"));
                default:
                    break;
            }
        }
        log.error("Erro de banco de dados", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Não foi possível concluir a operação no banco de dados");
    }

    private static ProblemDetail problem(HttpStatus status, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(status.getReasonPhrase());
        return pd;
    }

    private static <T extends Throwable> T findCause(Throwable ex, Class<T> type) {
        Throwable current = ex;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }
}
